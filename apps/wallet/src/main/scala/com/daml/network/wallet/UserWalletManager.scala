package com.daml.network.wallet

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.install.WalletAppInstall
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{HasHealth, Contract}
import com.daml.network.wallet.config.TreasuryConfig
import com.daml.network.wallet.store.{UserWalletStore, WalletStore}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/** Manages all services comprising an end-user wallets. */
class UserWalletManager(
    ledgerClient: CoinLedgerClient,
    val globalDomain: DomainAlias,
    private[wallet] val participantAdminConnection: ParticipantAdminConnection,
    val store: WalletStore,
    automationConfig: AutomationConfig,
    clock: Clock,
    treasuryConfig: TreasuryConfig,
    storage: Storage,
    retryProvider: CoinRetries,
    scanConnection: ScanConnection,
    override val loggerFactory: NamedLoggerFactory,
    timeouts: ProcessingTimeout,
    futureSupervisor: FutureSupervisor,
)(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer)
    extends AutoCloseable
    with NamedLogging
    with HasHealth {

  // map from end user name to end-user treasury service
  private[this] val endUserWalletsMap: scala.collection.concurrent.Map[String, UserWalletService] =
    TrieMap.empty

  /** Lookup an end-user's wallet.
    *
    * Succeeds if the user has been onboarded and its wallet has been initialized.
    */
  final def lookupUserWallet(endUserName: String): Option[UserWalletService] =
    endUserWalletsMap.get(endUserName)

  final def endUserWallets: Iterable[UserWalletService] = endUserWalletsMap.values

  /** Get or create the store for an end-user. Intended to be called when a user is onboarded.
    *
    * Do not use this in request handlers to avoid leaking resources.
    *
    * @return true, if a new end-user wallet was created
    */
  final def getOrCreateUserWallet(
      install: Contract[WalletAppInstall.ContractId, WalletAppInstall]
  ): Boolean = {
    val endUserName = install.payload.endUserName
    val endUserParty = PartyId.tryFromProtoPrimitive(install.payload.endUserParty)
    val key =
      UserWalletStore.Key(
        svcParty = store.key.svcParty,
        store.key.walletServiceParty,
        store.key.validatorParty,
        endUserName,
        endUserParty,
      )
    val walletService = new UserWalletService(
      ledgerClient,
      globalDomain,
      key,
      this,
      automationConfig,
      clock,
      treasuryConfig,
      storage,
      retryProvider,
      loggerFactory,
      scanConnection,
      timeouts,
      futureSupervisor,
    )

    endUserWalletsMap
      .putIfAbsent(endUserName, walletService)
      .fold(true)(_ => {
        walletService.close()
        false
      })
  }

  // NOTE: this function is exposed here in the UserWalletManager, as it requires joining data from all user-stores.
  /** Lists the validator reward coupons collectable by the current user (i.e. where they are the validator). */
  def listValidatorRewardCouponsCollectableBy(
      validatorUserStore: UserWalletStore,
      maxNumInputs: Option[Int],
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
                    .listSortedValidatorRewards(maxNumInputs, activeIssuingRounds)
              }
          }
        )
      validatorRewardCoupons <- Future.sequence(validatorRewardCouponsFs)
    } yield validatorRewardCoupons.flatten.take(maxNumInputs.getOrElse(Int.MaxValue))

  override def isHealthy: Boolean = endUserWalletsMap.values.forall(_.isHealthy)

  override def close(): Unit = Lifecycle.close(endUserWalletsMap.values.toSeq *)(logger)
}
