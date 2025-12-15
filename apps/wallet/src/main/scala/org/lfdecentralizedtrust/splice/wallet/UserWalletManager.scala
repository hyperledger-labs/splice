// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.WalletAppInstall
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupDuration
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
  Limit,
  LimitHelpers,
}
import org.lfdecentralizedtrust.splice.util.{Contract, HasHealth, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.wallet.config.{
  AutoAcceptTransfersConfig,
  TreasuryConfig,
  WalletSweepConfig,
}
import org.lfdecentralizedtrust.splice.wallet.store.{UserWalletStore, ValidatorLicenseStore}
import org.lfdecentralizedtrust.splice.wallet.util.ValidatorTopupConfig
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, blocking}

/** Manages all services comprising an end-user wallets. */
class UserWalletManager(
    ledgerClient: SpliceLedgerClient,
    val store: ValidatorLicenseStore,
    val validatorUser: String,
    val externalPartyWalletManager: ExternalPartyWalletManager,
    automationConfig: AutomationConfig,
    private[splice] val clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    treasuryConfig: TreasuryConfig,
    storage: DbStorage,
    retryProvider: RetryProvider,
    scanConnection: BftScanConnection,
    packageVersionSupport: PackageVersionSupport,
    override val loggerFactory: NamedLoggerFactory,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
    validatorTopupConfig: ValidatorTopupConfig,
    walletSweep: Map[String, WalletSweepConfig],
    autoAcceptTransfers: Map[String, AutoAcceptTransfersConfig],
    dedupDuration: DedupDuration,
    txLogBackfillEnabled: Boolean,
    txLogBackfillingBatchSize: Int,
    params: SpliceParametersConfig,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends AutoCloseable
    with NamedLogging
    with HasHealth
    with LimitHelpers {

  // map from end user party to end-user wallet service
  private[this] val endUserPartyWalletsMap
      : scala.collection.concurrent.Map[PartyId, (RetryProvider, UserWalletService)] =
    TrieMap.empty

  private[this] def removeEndUserPartyWallet(
      endUserParty: PartyId
  ): Option[(RetryProvider, UserWalletService)] =
    blocking {
      this.synchronized {
        endUserPartyWalletsMap.remove(endUserParty)
      }
    }

  // Note: putIfAbsent() eagerly evaluates the value to be inserted, but we only want to start
  // the new service if there is no existing service for the user yet.
  // Accessing a concurrent map while modifying it is safe, so we only need to synchronize adding
  // and removing users.
  private[this] def addEndUserPartyWallet(
      endUserParty: PartyId,
      createWallet: PartyId => (RetryProvider, UserWalletService),
  ): Option[(RetryProvider, UserWalletService)] = blocking {
    this.synchronized {
      if (endUserPartyWalletsMap.contains(endUserParty)) {
        logger.debug(
          show"Wallet for user party ${endUserParty} already exists, not creating a new one."
        )(TraceContext.empty)
        None
      } else {
        logger.debug(
          show"Creating wallet service and retry provider for user ${endUserParty}."
        )(TraceContext.empty)
        val endUserPartyWallet = createWallet(endUserParty)
        endUserPartyWalletsMap.put(endUserParty, endUserPartyWallet): Unit
        Some(endUserPartyWallet)
      }
    }
  }

  retryProvider.runOnShutdownWithPriority_(new RunOnClosing {
    override def name = s"set per-user retry providers as closed"
    override def done = false
    override def run()(implicit tc: TraceContext) = {
      endUserPartyWalletsMap.values.foreach { case (userRetryProvider, _) =>
        userRetryProvider.setAsClosing()
      }
    }
  })

  retryProvider.runOnOrAfterClose_(new RunOnClosing {
    override def name = s"shutdown per-user retry providers"
    // this is not perfectly precise, but RetryProvider.close is idempotent
    override def done = false
    override def run()(implicit tc: TraceContext) = {
      endUserPartyWalletsMap.values.foreach { case (userRetryProvider, _) =>
        userRetryProvider.close()
      }
    }
  })(TraceContext.empty)

  /** Lookup an end-user's wallet.
    *
    * Succeeds if the user has been onboarded and its wallet has been initialized.
    */
  // TODO(DACH-NY/canton-network-node#12550): move away from tracking onboarded users via on-ledger contracts, and create only one WalletAppInstall per user-party
  final def lookupUserWallet(
      endUserName: String
  )(implicit tc: TraceContext): Future[Option[UserWalletService]] = {
    store.lookupInstallByName(endUserName).flatMap {
      case None =>
        Future.successful(None)
      case Some(install) =>
        Future.successful(
          lookupEndUserPartyWallet(PartyId.tryFromProtoPrimitive(install.payload.endUserParty))
        )
    }
  }

  final def lookupEndUserPartyWallet(
      endUserParty: PartyId
  ): Option[UserWalletService] =
    endUserPartyWalletsMap.get(endUserParty).map(_._2)

  final def endUserPartyWallets: Iterable[(RetryProvider, UserWalletService)] =
    endUserPartyWalletsMap.values

  final def listEndUserParties: Seq[PartyId] =
    endUserPartyWallets.map(_._2.store.key.endUserParty).toSeq

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
      val endUserParty = PartyId.tryFromProtoPrimitive(install.payload.endUserParty)

      val userRetryProviderAndWalletService =
        addEndUserPartyWallet(endUserParty, createEndUserPartyWallet)

      // There might have been a concurrent call to .close() that missed the above addition of this user
      if (retryProvider.isClosing) {
        logger.debug(
          show"Detected race between adding wallet for party ${endUserParty} and shutdown: closing wallet."
        )(TraceContext.empty)
        userRetryProviderAndWalletService.foreach { case (userRetryProvider, walletService) =>
          userRetryProvider.close()
          walletService.close()
        }
        UnlessShutdown.AbortedDueToShutdown
      } else {
        UnlessShutdown.Outcome(userRetryProviderAndWalletService.isDefined)
      }
    }
  }

  private def createEndUserPartyWallet(
      endUserParty: PartyId
  ): (RetryProvider, UserWalletService) = {
    val key = UserWalletStore.Key(
      dsoParty = store.walletKey.dsoParty,
      store.walletKey.validatorParty,
      endUserParty,
    )
    val userLoggerFactory = loggerFactory.append("endUserParty", key.endUserParty.toString)
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
      key,
      this,
      automationConfig,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      treasuryConfig,
      storage,
      userRetryProvider,
      userLoggerFactory,
      scanConnection,
      packageVersionSupport,
      domainMigrationInfo,
      participantId,
      ingestFromParticipantBegin,
      ingestUpdateHistoryFromParticipantBegin,
      Option.when(endUserParty == store.walletKey.validatorParty)(validatorTopupConfig),
      // TODO(DACH-NY/canton-network-node#12554): make it easier to configure the sweep functionality and guard better against operator errors (typos, etc.)
      walletSweep.get(endUserParty.toProtoPrimitive),
      autoAcceptTransfers.get(endUserParty.toProtoPrimitive),
      dedupDuration,
      txLogBackfillEnabled = txLogBackfillEnabled,
      txLogBackfillingBatchSize = txLogBackfillingBatchSize,
      params,
    )
    (userRetryProvider, walletService)
  }

  def offboardUserParty(userParty: PartyId) = {
    removeEndUserPartyWallet(userParty) match {
      case None =>
        throw Status.NOT_FOUND
          .withDescription(show"No wallet service found for user party ${userParty}")
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
  )(implicit tc: TraceContext): Future[
    Seq[
      Contract[amuletCodegen.ValidatorRewardCoupon.ContractId, amuletCodegen.ValidatorRewardCoupon]
    ]
  ] =
    for {
      validatorRights <- validatorUserStore.getValidatorRightsWhereUserIsValidator()
      hostedUsers = validatorRights.map(c => PartyId.tryFromProtoPrimitive(c.payload.user)).toSet
      validatorRewardCouponsFs: Seq[
        Future[Seq[
          Contract[
            amuletCodegen.ValidatorRewardCoupon.ContractId,
            amuletCodegen.ValidatorRewardCoupon,
          ]
        ]]
      ] = hostedUsers.toSeq
        .map(endUserParty =>
          // TODO(M3-83): Avoid the application-level join and get the rewards in one go from the DB.
          this.lookupEndUserPartyWallet(endUserParty) match {
            case None =>
              this.externalPartyWalletManager.lookupExternalPartyWallet(endUserParty) match {
                case None =>
                  logger.info(
                    show"Might miss validator rewards as the party ${endUserParty} is not (yet) setup as either a local or external party."
                  )
                  Future.successful(Seq.empty)
                case Some(walletOfExternalParty) =>
                  walletOfExternalParty.store
                    .listSortedValidatorRewards(activeIssuingRounds, limit)
              }
            case Some(walletOfHostedUser) =>
              walletOfHostedUser.store
                .listSortedValidatorRewards(activeIssuingRounds, limit)
          }
        )
      validatorRewardCoupons <- Future.sequence(validatorRewardCouponsFs)
    } yield applyLimit(
      "listValidatorRewardCouponsCollectableBy",
      limit,
      validatorRewardCoupons.flatten,
    )

  override def isHealthy: Boolean = endUserPartyWalletsMap.values.forall(_._2.isHealthy)

  override def close(): Unit = LifeCycle.close(
    // per-user retry providers should have been closed by the shutdown signal, so only closing the services here
    endUserPartyWalletsMap.values.map(_._2).toSeq*
  )(logger)
}
