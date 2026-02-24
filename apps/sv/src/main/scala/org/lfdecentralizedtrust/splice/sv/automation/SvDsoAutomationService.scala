// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.{Clock, WallClock}
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  AutomationServiceCompanion,
  SpliceAppAutomationService,
}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.{
  aTrigger,
  TriggerClass,
}
import org.lfdecentralizedtrust.splice.config.{
  EnabledFeaturesConfig,
  SpliceInstanceNamesConfig,
  UpgradesConfig,
}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.{BftSequencerConfig, LocalSynchronizerNode}
import org.lfdecentralizedtrust.splice.sv.automation.SvDsoAutomationService.{
  LocalSequencerClientConfig,
  LocalSequencerClientContext,
}
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.*
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.*
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding.{
  SvOffboardingMediatorTrigger,
  SvOffboardingPartyToParticipantProposalTrigger,
  SvOffboardingSequencerTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.*
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftNode
import org.lfdecentralizedtrust.splice.sv.config.{SequencerPruningConfig, SvAppBackendConfig}
import org.lfdecentralizedtrust.splice.sv.lsu.{
  LogicalSynchronizerUpgradeAnnouncementTrigger,
  LogicalSynchronizerUpgradeTrigger,
  LogicalSyncUpgradeTransferTrafficTrigger,
}
import org.lfdecentralizedtrust.splice.sv.migration.DecentralizedSynchronizerMigrationTrigger
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import java.nio.file.Path
import scala.concurrent.ExecutionContextExecutor

class SvDsoAutomationService(
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    dsoStore: SvDsoStore,
    ledgerClient: SpliceLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    cometBft: Option[CometBftNode],
    localSynchronizerNodes: Option[LocalSynchronizerNodes],
    upgradesConfig: UpgradesConfig,
    spliceInstanceNamesConfig: SpliceInstanceNamesConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    packageVersionSupport: PackageVersionSupport,
    enabledFeatures: EnabledFeaturesConfig,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends SpliceAppAutomationService(
      config.automation,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      dsoStore,
      ledgerClient,
      retryProvider,
      config.parameters,
    ) {

  override def companion
      : org.lfdecentralizedtrust.splice.sv.automation.SvDsoAutomationService.type =
    SvDsoAutomationService

  // notice the absence of UpdateHistory: the history for the dso party is duplicate with Scan

  private[splice] val restartDsoDelegateBasedAutomationTrigger =
    new RestartDsoDelegateBasedAutomationTrigger(
      triggerContext,
      domainTimeSync,
      domainUnpausedSync,
      dsoStore,
      connection,
      clock,
      config,
      retryProvider,
      packageVersionSupport,
    )

  // required for triggers that must run in sim time as well
  private val wallClockTriggerContext = triggerContext
    .focus(_.clock)
    .replace(
      new WallClock(triggerContext.timeouts, triggerContext.loggerFactory)
    )

  private val onboardingTriggerContext = wallClockTriggerContext
    .focus(_.config.pollingInterval)
    .replace(
      config.onboardingPollingInterval.getOrElse(wallClockTriggerContext.config.pollingInterval)
    )

  // Triggers that require namespace permissions and the existence of the DsoRules and AmuletRules contracts
  def registerPostOnboardingTriggers(): Unit = {
    registerTrigger(
      new SvOnboardingRequestTrigger(
        triggerContext,
        dsoStore,
        svStore,
        config,
        connection(SpliceLedgerConnectionPriority.High),
      )
    )
    // Register optional BFT triggers
    cometBft.foreach { node =>
      if (triggerContext.config.enableCometbftReconciliation) {
        registerTrigger(
          new PublishLocalCometBftNodeConfigTrigger(
            triggerContext,
            dsoStore,
            connection(SpliceLedgerConnectionPriority.High),
            node,
          )
        )
        registerTrigger(
          new ReconcileCometBftNetworkConfigWithDsoRulesTrigger(
            triggerContext,
            dsoStore,
            node,
          )
        )
      }
    }
    registerTrigger(
      new SvOffboardingPartyToParticipantProposalTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOffboardingMediatorTrigger(
        wallClockTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOffboardingSequencerTrigger(
        wallClockTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingPromoteParticipantToSubmitterTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
        config.enableOnboardingParticipantPromotionDelay,
      )
    )
    registerTrigger(
      new SvOnboardingPartyToParticipantProposalTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingSequencerTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingMediatorProposalTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )

    registerTrigger(
      new SvNamespaceMembershipTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )

    (localSynchronizerNodes, config.domainMigrationDumpPath) match {
      case (Some(synchronizerNode), Some(dumpPath)) =>
        registerTrigger(
          new DecentralizedSynchronizerMigrationTrigger(
            config.domainMigrationId,
            triggerContext,
            config.domains.global.alias,
            synchronizerNode.current,
            dsoStore,
            connection(SpliceLedgerConnectionPriority.High),
            participantAdminConnection,
            synchronizerNode.current.sequencerAdminConnection,
            dumpPath: Path,
            enabledFeatures,
          )
        )
      case _ => ()
    }

    (localSynchronizerNodes.map(_.current), localSynchronizerNodes.flatMap(_.successor)) match {
      case (Some(currentSynchronizerNode), Some(successorSynchronizerNode)) =>
        registerTrigger(
          new LogicalSynchronizerUpgradeTrigger(
            triggerContext,
            currentSynchronizerNode,
            successorSynchronizerNode,
          )
        )
        registerTrigger(
          new LogicalSyncUpgradeTransferTrafficTrigger(
            triggerContext,
            currentSynchronizerNode,
            successorSynchronizerNode,
          )
        )
      case _ => ()
    }

    registerTrigger(
      new ReconcileDynamicSynchronizerParametersTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
        config,
      )
    )

    lazy val aggregatingScanConnection = new AggregatingScanConnection(
      dsoStore,
      upgradesConfig,
      triggerContext.clock,
      triggerContext.retryProvider,
      triggerContext.loggerFactory,
    )
    // TODO(#564) - account for PSID in the reconciliation
    // TODO(#564) - add check for sequencer status in the triggers
    localSynchronizerNodes.foreach {
      def registerTriggersForSynchronizers(current: LocalSynchronizerNode): Unit = {
        registerTrigger(
          new LogicalSynchronizerUpgradeAnnouncementTrigger(
            triggerContext,
            config.scheduledLsu,
            participantAdminConnection,
            config.domains.global.alias,
          )
        )
        current.sequencerConfig match {
          case BftSequencerConfig() =>
            registerTrigger(
              new SvBftSequencerPeerOffboardingTrigger(
                triggerContext,
                dsoStore,
                current.sequencerAdminConnection,
                aggregatingScanConnection,
                config.domainMigrationId,
              )
            )
            registerTrigger(
              new SvBftSequencerPeerOnboardingTrigger(
                triggerContext,
                dsoStore,
                current.sequencerAdminConnection,
                aggregatingScanConnection,
                config.domainMigrationId,
              )
            )
          case _ =>
        }
      }

      synchronizerNode =>
        registerTriggersForSynchronizers(synchronizerNode.current)
        synchronizerNode.successor.foreach(registerTriggersForSynchronizers)
    }
  }

  def registerTrafficReconciliationTriggers(): Unit = {
    localSynchronizerNodes
      .map(_.current)
      .foreach(current =>
        registerTrigger(
          new ReconcileSequencerLimitWithMemberTrafficTrigger(
            triggerContext,
            dsoStore,
            current.sequencerAdminConnection,
            config.trafficBalanceReconciliationDelay,
          )
        )
      )
    localSynchronizerNodes.flatMap(_.successor).foreach { successor =>
      registerTrigger(
        new ReconcileSequencerLimitWithMemberTrafficTrigger(
          triggerContext,
          dsoStore,
          successor.sequencerAdminConnection,
          config.trafficBalanceReconciliationDelay,
        )
      )
    }
    registerTrigger(
      new SvOnboardingUnlimitedTrafficTrigger(
        onboardingTriggerContext,
        dsoStore,
        localSynchronizerNodes.map(_.current).map(_.sequencerAdminConnection),
        config.trafficBalanceReconciliationDelay,
      )
    )
  }

  def registerPostUnlimitedTrafficTriggers(): Unit = {
    registerTrigger(
      new SummarizingMiningRoundTrigger(
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Medium),
      )
    )
    registerTrigger(
      new ReceiveSvRewardCouponTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
        connection(SpliceLedgerConnectionPriority.High),
        config.extraBeneficiaries,
      )
    )
    if (config.automation.enableClosedRoundArchival)
      registerTrigger(
        new ArchiveClosedMiningRoundsTrigger(
          triggerContext,
          dsoStore,
          connection(SpliceLedgerConnectionPriority.Low),
        )
      )

    registerTrigger(restartDsoDelegateBasedAutomationTrigger)

    registerTrigger(
      new AnsSubscriptionInitialPaymentTrigger(
        triggerContext,
        dsoStore,
        spliceInstanceNamesConfig,
        connection(SpliceLedgerConnectionPriority.Medium),
      )
    )
    registerTrigger(
      new SvPackageVettingTrigger(
        participantAdminConnection,
        dsoStore,
        triggerContext,
        config.maxVettingDelay,
        config.latestPackagesOnly,
      )
    )

    // SV status report triggers
    registerTrigger(
      new SubmitSvStatusReportTrigger(
        config,
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Medium),
        cometBft,
        // we publish the mediator lower time bound, fine to freeze after a LSU until we update the current sync config
        localSynchronizerNodes.map(_.current).map(_.mediatorAdminConnection),
        participantAdminConnection,
      )
    )
    registerTrigger(
      new ReportSvStatusMetricsExportTrigger(
        triggerContext,
        dsoStore,
        cometBft,
      )
    )
    registerTrigger(
      new ReportValidatorLicenseMetricsExportTrigger(
        triggerContext,
        dsoStore,
      )
    )
    registerTrigger(
      new TransferCommandCounterTrigger(
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Low),
      )
    )
    registerTrigger(
      new AmuletPriceMetricsTrigger(
        triggerContext,
        dsoStore,
      )
    )

    registerTrigger(
      new PublishScanConfigTrigger(
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Low),
        config.scan,
        upgradesConfig,
      )
    )

    config.followAmuletConversionRateFeed.foreach { c =>
      registerTrigger(
        new FollowAmuletConversionRateFeedTrigger(
          triggerContext,
          dsoStore,
          connection(SpliceLedgerConnectionPriority.Low),
          c,
        )
      )
    }
  }

  private val localSequencerClientContext: Option[LocalSequencerClientContext] =
    localSynchronizerNodes
      .map(_.current)
      .map(cfg =>
        LocalSequencerClientContext(
          cfg.sequencerAdminConnection,
          cfg.mediatorAdminConnection,
          Some(
            LocalSequencerClientConfig(
              cfg.sequencerInternalConfig,
              config.domains.global.alias,
            )
          ),
          cfg.sequencerPruningConfig.map(pruningConfig =>
            SequencerPruningConfig(
              pruningConfig.pruningInterval,
              pruningConfig.retentionPeriod,
            )
          ),
        )
      )

  if (!config.bftSequencerConnection) {
    localSynchronizerNodes.map(_.current).foreach { node =>
      registerTrigger(
        new LocalSequencerConnectionsTrigger(
          triggerContext,
          participantAdminConnection,
          config.domains.global.alias,
          dsoStore,
          node,
          config.participantClient.sequencerRequestAmplification.toInternal,
          config.domainMigrationId,
          newSequencerConnectionPool = enabledFeatures.newSequencerConnectionPool,
        )
      )
    }
  }

  // fine to run the trigger only for the current sync as after a LSU we don't have anything to prune yet
  localSequencerClientContext.foreach { sequencerContext =>
    sequencerContext.pruningConfig.foreach { pruningConfig =>
      val contextWithSpecificPolling = triggerContext.copy(
        config = triggerContext.config.copy(
          pollingInterval = pruningConfig.pruningInterval
        )
      )
      registerTrigger(
        new SequencerPruningTrigger(
          contextWithSpecificPolling,
          dsoStore,
          config.scan,
          upgradesConfig,
          sequencerContext.sequencerAdminConnection,
          sequencerContext.mediatorAdminConnection,
          clock,
          pruningConfig.retentionPeriod,
          participantAdminConnection,
          config.domainMigrationId,
        )
      )
    }
  }
}

object SvDsoAutomationService extends AutomationServiceCompanion {
  case class LocalSequencerClientContext(
      sequencerAdminConnection: SequencerAdminConnection,
      mediatorAdminConnection: MediatorAdminConnection,
      internalClientConfig: Option[LocalSequencerClientConfig],
      pruningConfig: Option[SequencerPruningConfig] = None,
  )

  case class LocalSequencerClientConfig(
      sequencerInternalConfig: ClientConfig,
      decentralizedSynchronizerAlias: SynchronizerAlias,
  )

  // defined because some triggers are registered later by
  // registerPostOnboardingTriggers
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    SpliceAppAutomationService.expectedTriggerClasses ++ Seq(
      aTrigger[SummarizingMiningRoundTrigger],
      aTrigger[SvOnboardingRequestTrigger],
      aTrigger[ReceiveSvRewardCouponTrigger],
      aTrigger[ArchiveClosedMiningRoundsTrigger],
      aTrigger[RestartDsoDelegateBasedAutomationTrigger],
      aTrigger[AnsSubscriptionInitialPaymentTrigger],
      aTrigger[SvPackageVettingTrigger],
      aTrigger[SvOffboardingPartyToParticipantProposalTrigger],
      aTrigger[SvOffboardingMediatorTrigger],
      aTrigger[SvOnboardingUnlimitedTrafficTrigger],
      aTrigger[SvOffboardingSequencerTrigger],
      aTrigger[ReconcileSequencerLimitWithMemberTrafficTrigger],
      aTrigger[SvNamespaceMembershipTrigger],
      aTrigger[SvOnboardingPromoteParticipantToSubmitterTrigger],
      aTrigger[SvOnboardingPartyToParticipantProposalTrigger],
      aTrigger[SvOnboardingSequencerTrigger],
      aTrigger[SvOnboardingMediatorProposalTrigger],
      aTrigger[DecentralizedSynchronizerMigrationTrigger],
      aTrigger[PublishLocalCometBftNodeConfigTrigger],
      aTrigger[PublishScanConfigTrigger],
      aTrigger[ReconcileCometBftNetworkConfigWithDsoRulesTrigger],
      aTrigger[LocalSequencerConnectionsTrigger],
      aTrigger[SequencerPruningTrigger],
      aTrigger[SubmitSvStatusReportTrigger],
      aTrigger[ReportSvStatusMetricsExportTrigger],
      aTrigger[ReportValidatorLicenseMetricsExportTrigger],
      aTrigger[ReconcileDynamicSynchronizerParametersTrigger],
      aTrigger[TransferCommandCounterTrigger],
      aTrigger[SvBftSequencerPeerOffboardingTrigger],
      aTrigger[SvBftSequencerPeerOnboardingTrigger],
      aTrigger[FollowAmuletConversionRateFeedTrigger],
      aTrigger[AmuletPriceMetricsTrigger],
      aTrigger[LogicalSynchronizerUpgradeTrigger],
      aTrigger[LogicalSynchronizerUpgradeAnnouncementTrigger],
      aTrigger[LogicalSyncUpgradeTransferTrafficTrigger],
    )
}
