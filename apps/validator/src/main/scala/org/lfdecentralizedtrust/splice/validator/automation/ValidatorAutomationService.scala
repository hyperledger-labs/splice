// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import org.lfdecentralizedtrust.splice.automation.{
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  SqlIndexInitializationTrigger,
}
import org.lfdecentralizedtrust.splice.config.{
  AutomationConfig,
  EnabledFeaturesConfig,
  PeriodicBackupDumpConfig,
  SpliceParametersConfig,
}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesStore
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.validator.domain.DomainConnector
import org.lfdecentralizedtrust.splice.validator.migration.DecentralizedSynchronizerMigrationTrigger
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import org.lfdecentralizedtrust.splice.wallet.UserWalletManager
import org.lfdecentralizedtrust.splice.wallet.automation.{
  OffboardUserPartyTrigger,
  ValidatorRightTrigger,
  WalletAppInstallTrigger,
}
import org.lfdecentralizedtrust.splice.wallet.config.TransferPreapprovalConfig
import org.lfdecentralizedtrust.splice.wallet.util.ValidatorTopupConfig
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.nio.file.Path
import scala.concurrent.ExecutionContextExecutor

class ValidatorAutomationService(
    automationConfig: AutomationConfig,
    backupDumpConfig: Option[PeriodicBackupDumpConfig],
    validatorTopupConfig: ValidatorTopupConfig,
    grpcDeadline: Option[NonNegativeFiniteDuration],
    transferPreapprovalConfig: TransferPreapprovalConfig,
    sequencerConnectionFromScan: Boolean,
    isSvValidator: Boolean,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    walletManagerOpt: Option[UserWalletManager], // None when config.enableWallet=false
    store: ValidatorStore,
    val updateHistory: UpdateHistory,
    storage: DbStorage,
    scanConnection: BftScanConnection,
    ledgerClient: SpliceLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    participantIdentitiesStore: NodeIdentitiesStore,
    domainConnector: DomainConnector,
    domainMigrationDumpPath: Option[Path],
    domainMigrationId: Long,
    retryProvider: RetryProvider,
    svValidator: Boolean,
    sequencerSubmissionAmplificationPatience: NonNegativeFiniteDuration,
    contactPoint: String,
    initialSynchronizerTime: Option[CantonTimestamp],
    maxVettingDelay: NonNegativeFiniteDuration,
    params: SpliceParametersConfig,
    latestPackagesOnly: Boolean,
    enabledFeatures: EnabledFeaturesConfig,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends SpliceAppAutomationService(
      automationConfig,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      store,
      ledgerClient,
      retryProvider,
      params,
    ) {
  override def companion
      : org.lfdecentralizedtrust.splice.validator.automation.ValidatorAutomationService.type =
    ValidatorAutomationService

  registerUpdateHistoryIngestion(updateHistory)

  automationConfig.topologyMetricsPollingInterval.foreach(topologyPollingInterval =>
    registerTrigger(
      new TopologyMetricsTrigger(
        triggerContext
          .focus(_.config.pollingInterval)
          .replace(topologyPollingInterval),
        scanConnection,
        participantAdminConnection,
      )
    )
  )

  walletManagerOpt.foreach { walletManager =>
    registerTrigger(
      new WalletAppInstallTrigger(
        triggerContext,
        walletManager,
        connection(
          SpliceLedgerConnectionPriority.Low
        ),
      )
    )
    registerTrigger(
      new ValidatorRightTrigger(
        triggerContext,
        walletManager.externalPartyWalletManager,
        connection(SpliceLedgerConnectionPriority.Medium),
        participantAdminConnection,
      )
    )

    registerTrigger(
      new OffboardUserPartyTrigger(
        triggerContext,
        walletManager,
        connection(SpliceLedgerConnectionPriority.Medium),
      )
    )

    registerTrigger(
      new AcceptTransferPreapprovalProposalTrigger(
        triggerContext,
        store,
        walletManager,
        transferPreapprovalConfig,
        clock,
      )
    )

    registerTrigger(
      new RenewTransferPreapprovalTrigger(
        triggerContext,
        store,
        walletManager,
        transferPreapprovalConfig,
      )
    )

    if (automationConfig.enableAutomaticRewardsCollectionAndAmuletMerging) {
      registerTrigger(
        new ReceiveFaucetCouponTrigger(
          triggerContext,
          scanConnection,
          store,
          walletManager,
          validatorTopupConfig,
          connection(SpliceLedgerConnectionPriority.Medium),
          clock,
        )
      )
    }

    if (isSvValidator)
      logger.info(
        s"Not starting TopupMemberTrafficTrigger, as this is an SV validator."
      )(TraceContext.empty)
    else if (validatorTopupConfig.targetThroughput.value <= 0L)
      logger.info(
        s"Not starting TopupMemberTrafficTrigger, as the validator is not configured to buy extra traffic."
      )(TraceContext.empty)
    else
      registerTrigger(
        new TopupMemberTrafficTrigger(
          triggerContext
            .focus(_.config.pollingInterval)
            .replace(triggerContext.config.topupTriggerPollingInterval_),
          store,
          connection(SpliceLedgerConnectionPriority.High),
          participantAdminConnection,
          validatorTopupConfig,
          grpcDeadline,
          clock,
          walletManager,
          scanConnection,
          domainMigrationId,
        )
      )

    registerTrigger(
      new TransferCommandSendTrigger(
        triggerContext,
        scanConnection,
        store,
        walletManager.externalPartyWalletManager,
        connection(SpliceLedgerConnectionPriority.Medium),
      )
    )
  }

  backupDumpConfig.foreach(config =>
    registerTrigger(
      new PeriodicParticipantIdentitiesBackupTrigger(
        config,
        triggerContext,
        participantIdentitiesStore,
      )
    )
  )

  if (sequencerConnectionFromScan)
    registerTrigger(
      new ReconcileSequencerConnectionsTrigger(
        triggerContext,
        participantAdminConnection,
        scanConnection,
        domainConnector,
        sequencerSubmissionAmplificationPatience,
        initialSynchronizerTime,
        newSequencerConnectionPool = enabledFeatures.newSequencerConnectionPool,
      )
    )

  registerTrigger(
    new ValidatorPackageVettingTrigger(
      participantAdminConnection,
      scanConnection,
      triggerContext,
      maxVettingDelay,
      latestPackagesOnly,
    )
  )

  registerTrigger(
    new ValidatorLicenseMetadataTrigger(
      triggerContext,
      connection(SpliceLedgerConnectionPriority.Low),
      store,
      contactPoint,
    )
  )

  registerTrigger(
    new ValidatorLicenseActivityTrigger(
      triggerContext,
      connection(SpliceLedgerConnectionPriority.Low),
      store,
    )
  )

  if (!svValidator) {
    domainMigrationDumpPath.fold(
      logger.info(
        "Not starting SynchronizerUpgradeTrigger, as no domain migration dump path is configured."
      )(TraceContext.empty)
    ) { path =>
      registerTrigger(
        new DecentralizedSynchronizerMigrationTrigger(
          domainMigrationId,
          triggerContext,
          connection(SpliceLedgerConnectionPriority.Medium),
          participantAdminConnection,
          path,
          scanConnection,
          enabledFeatures,
        )
      )
    }
  }

  registerTrigger(
    SqlIndexInitializationTrigger(
      storage,
      triggerContext,
    )
  )
}

object ValidatorAutomationService extends AutomationServiceCompanion {
  override protected[this] def expectedTriggerClasses: Seq[Nothing] = Seq.empty
}
