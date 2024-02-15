package com.daml.network.wallet

import org.apache.pekko.stream.Materializer
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.install.WalletAppInstall
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, ParticipantAdminConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.store.Limit
import com.daml.network.util.{Contract, HasHealth, TemplateJsonDecoder}
import com.daml.network.wallet.config.TreasuryConfig
import com.daml.network.wallet.store.{UserWalletStore, WalletStore}
import com.digitalasset.canton.lifecycle.{RunOnShutdown, *}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/** Manages all services comprising an end-user wallets. */
class UserWalletManager(
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    val store: WalletStore,
    val validatorUser: String,
    automationConfig: AutomationConfig,
    clock: Clock,
    treasuryConfig: TreasuryConfig,
    storage: Storage,
    retryProvider: RetryProvider,
    scanConnection: BftScanConnection,
    override val loggerFactory: NamedLoggerFactory,
    // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    close: CloseContext,
) extends AutoCloseable
    with NamedLogging
    with HasHealth {

  // map from end user name to end-user treasury service
  private[this] val endUserWalletsMap
      : scala.collection.concurrent.Map[String, (RetryProvider, UserWalletService)] =
    TrieMap.empty

  retryProvider.runOnShutdownWithPriority_(new RunOnShutdown {
    override def name = s"set per-user retry providers as closed"
    override def done = false
    override def run() = {
      endUserWalletsMap.values.foreach { case (userRetryProvider, _) =>
        userRetryProvider.setAsClosing()
      }
    }
  })(TraceContext.empty)

  retryProvider.runOnShutdown_(new RunOnShutdown {
    override def name = s"shutdown per-user retry providers"
    // this is not perfectly precise, but RetryProvider.close is idempotent
    override def done = false
    override def run() = {
      endUserWalletsMap.values.foreach { case (userRetryProvider, _) =>
        userRetryProvider.close()
      }
    }
  })(TraceContext.empty)

  /** Lookup an end-user's wallet.
    *
    * Succeeds if the user has been onboarded and its wallet has been initialized.
    */
  final def lookupUserWallet(endUserName: String): Option[UserWalletService] =
    endUserWalletsMap.get(endUserName).map(_._2)

  final def endUserWallets: Iterable[(RetryProvider, UserWalletService)] = endUserWalletsMap.values

  final def listUsers: Seq[String] = endUserWallets.map(_._2.store.key.endUserName).toSeq

  /** Get or create the store for an end-user. Intended to be called when a user is onboarded.
    *
    * Do not use this in request handlers to avoid leaking resources.
    *
    * @return true, if a new end-user wallet was created
    */
  final def getOrCreateUserWallet(
      install: Contract[WalletAppInstall.ContractId, WalletAppInstall]
  ): UnlessShutdown[Boolean] = {
    if (retryProvider.isClosing) {
      UnlessShutdown.AbortedDueToShutdown
    } else {
      val endUserName = install.payload.endUserName
      val endUserParty = PartyId.tryFromProtoPrimitive(install.payload.endUserParty)
      val key =
        UserWalletStore.Key(
          svcParty = store.walletKey.svcParty,
          store.walletKey.validatorParty,
          endUserName,
          endUserParty,
        )
      val userLoggerFactory = loggerFactory.append("user", key.endUserName)
      // We allocate a separate retry provider per user, since users can also be offboarded (thus their service closed)
      // without the entire node going down.
      val userRetryProvider =
        RetryProvider(
          userLoggerFactory,
          retryProvider.timeouts,
          retryProvider.futureSupervisor,
          retryProvider.metricsFactory,
        )
      val walletService = new UserWalletService(
        ledgerClient,
        participantAdminConnection,
        key,
        this,
        automationConfig,
        clock,
        treasuryConfig,
        storage,
        userRetryProvider,
        userLoggerFactory,
        scanConnection,
        domainMigrationId,
      )

      val wasUserAdded = endUserWalletsMap
        .putIfAbsent(endUserName, (userRetryProvider, walletService))
        .fold(true)(_ => {
          // User was already there, close the newly created wallet.
          userRetryProvider.close()
          walletService.close()
          false
        })
      // There might have been a concurrent call to .close() that missed the above addition of this user
      if (retryProvider.isClosing) {
        logger.debug(
          show"Detected race between adding wallet for user ${endUserName.singleQuoted} and shutdown: closing wallet."
        )(TraceContext.empty)
        userRetryProvider.close()
        walletService.close()
        UnlessShutdown.AbortedDueToShutdown
      } else {
        UnlessShutdown.Outcome(wasUserAdded)
      }
    }
  }

  def offboardUser(username: String): Unit = {
    endUserWalletsMap.remove(username) match {
      case None =>
        throw Status.NOT_FOUND
          .withDescription(s"No wallet service found for user ${username}")
          .asRuntimeException()
      case Some((userRetryProvider, walletService)) =>
        userRetryProvider.close()
        walletService.close()
    }
  }

  // NOTE: this function is exposed here in the UserWalletManager, as it requires joining data from all user-stores.
  /** Lists the validator reward coupons collectable by the current user (i.e. where they are the validator). */
  def listValidatorRewardCouponsCollectableBy(
      validatorUserStore: UserWalletStore,
      limit: Limit,
      activeIssuingRounds: Option[Set[Long]],
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[
      Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
    ]
  ] =
    for {
      validatorRights <- validatorUserStore.getValidatorRightsWhereUserIsValidator()
      hostedUsers = validatorRights.map(c => PartyId.tryFromProtoPrimitive(c.payload.user)).toSet
      validatorRewardCouponsFs: Seq[
        Future[Seq[
          Contract[
            coinCodegen.ValidatorRewardCoupon.ContractId,
            coinCodegen.ValidatorRewardCoupon,
          ]
        ]]
      ] = hostedUsers.toSeq
        .map(u =>
          store.lookupInstallByParty(u).flatMap {
            case None =>
              // This can happen if the ingestion of the corresponding WalletAppInstall contract
              // has not yet completed. Ignoring the reward is perfectly fine, we
              // will pick it up next time.
              logger.info(
                s"ValidatorRight of ${validatorUserStore.key.endUserParty} for end-user party $u has no associated WalletAppInstall contract, ignoring."
              )
              Future.successful(Seq.empty)
            case Some(install) =>
              // TODO(M3-83): Avoid the application-level join and get the rewards in one go from the DB.
              this.lookupUserWallet(install.payload.endUserName) match {
                case None =>
                  logger.info(
                    s"Might miss validator rewards as the UserWalletStore for end-user name ${install.payload.endUserName} is not (yet) setup."
                  )
                  Future.successful(Seq.empty)
                case Some(walletOfHostedUser) =>
                  walletOfHostedUser.store
                    .listSortedValidatorRewards(activeIssuingRounds, limit)
              }
          }
        )
      validatorRewardCoupons <- Future.sequence(validatorRewardCouponsFs)
    } yield validatorRewardCoupons.flatten.take(limit.limit)

  override def isHealthy: Boolean = endUserWalletsMap.values.forall(_._2.isHealthy)

  override def close(): Unit = Lifecycle.close(
    // per-user retry providers should have been closed by the shutdown signal, so only closing the services here
    endUserWalletsMap.values.map(_._2).toSeq *
  )(logger)
}
