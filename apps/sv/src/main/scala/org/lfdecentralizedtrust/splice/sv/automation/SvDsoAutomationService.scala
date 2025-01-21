// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import org.lfdecentralizedtrust.splice.automation.{
  AmuletConfigReassignmentTrigger,
  AssignTrigger,
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  TransferFollowTrigger,
}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.{
  TriggerClass,
  aTrigger,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.config.{SpliceInstanceNamesConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ConstrainedTemplate
import org.lfdecentralizedtrust.splice.sv.{ExtraSynchronizerNode, LocalSynchronizerNode}
import org.lfdecentralizedtrust.splice.sv.automation.SvDsoAutomationService.{
  LocalSequencerClientConfig,
  LocalSequencerClientContext,
}
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.*
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.*
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvNamespaceMembershipTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding.{
  SvOffboardingMediatorTrigger,
  SvOffboardingPartyToParticipantProposalTrigger,
  SvOffboardingSequencerTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.*
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftNode
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.{FoundDso, InitialPackageConfig}
import org.lfdecentralizedtrust.splice.sv.config.{SequencerPruningConfig, SvAppBackendConfig}
import org.lfdecentralizedtrust.splice.sv.migration.DecentralizedSynchronizerMigrationTrigger
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.util.{QualifiedName, TemplateJsonDecoder}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps
import org.apache.pekko.stream.Materializer

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
    localSynchronizerNode: Option[LocalSynchronizerNode],
    extraSynchronizerNodes: Map[String, ExtraSynchronizerNode],
    upgradesConfig: UpgradesConfig,
    spliceInstanceNamesConfig: SpliceInstanceNamesConfig,
    override protected val loggerFactory: NamedLoggerFactory,
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
      PackageIdResolver
        .inferFromAmuletRules(
          clock,
          dsoStore,
          loggerFactory,
          SvDsoAutomationService.bootstrapPackageIdResolver(config.onboarding match {
            case Some(foundDso: FoundDso) => Some(foundDso.initialPackageConfig)
            case _ => None
          }),
        ),
      ledgerClient,
      retryProvider,
      config.ingestFromParticipantBegin,
      config.ingestUpdateHistoryFromParticipantBegin,
    ) {

  override def companion
      : org.lfdecentralizedtrust.splice.sv.automation.SvDsoAutomationService.type =
    SvDsoAutomationService

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

  // Trigger that starts only after the SV namespace is added to the decentralized namespace
  def registerSvNamespaceMembershipTrigger(): Unit = {
    registerTrigger(
      new SvNamespaceMembershipTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
  }

  // Triggers that require namespace permissions and the existence of the DsoRules and AmuletRules contracts
  def registerPostOnboardingTriggers(): Unit = {
    registerTrigger(
      new SvOnboardingRequestTrigger(triggerContext, dsoStore, svStore, config, connection)
    )
    // Register optional BFT triggers
    cometBft.foreach { node =>
      if (triggerContext.config.enableCometbftReconciliation) {
        registerTrigger(
          new PublishLocalCometBftNodeConfigTrigger(
            triggerContext,
            dsoStore,
            connection,
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

    (localSynchronizerNode, config.domainMigrationDumpPath) match {
      case (Some(synchronizerNode), Some(dumpPath)) =>
        registerTrigger(
          new DecentralizedSynchronizerMigrationTrigger(
            config.domainMigrationId,
            triggerContext,
            config.domains.global.alias,
            synchronizerNode,
            dsoStore,
            participantAdminConnection,
            synchronizerNode.sequencerAdminConnection,
            dumpPath: Path,
          )
        )
      case _ => ()
    }
    registerTrigger(
      new ReconcileDynamicDomainParametersTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
        config.submissionTimeRecordTimeTolerance,
        config.mediatorDeduplicationTimeout,
      )
    )

    if (config.supportsSoftDomainMigrationPoc) {
      registerTrigger(
        new AmuletConfigReassignmentTrigger(
          triggerContext,
          dsoStore,
          connection,
          dsoStore.key.dsoParty,
          Seq[ConstrainedTemplate](
            AmuletRules.COMPANION,
            OpenMiningRound.COMPANION,
            IssuingMiningRound.COMPANION,
          ),
          (tc: TraceContext) => dsoStore.lookupAmuletRules()(tc),
        )
      )

      registerTrigger(
        new SignSynchronizerBootstrappingStateTrigger(
          dsoStore,
          participantAdminConnection,
          triggerContext,
          localSynchronizerNode.getOrElse(
            throw Status.INTERNAL
              .withDescription("Soft domain migrations require a configured synchronizer node")
              .asRuntimeException
          ),
          extraSynchronizerNodes,
          upgradesConfig,
        )
      )

      registerTrigger(
        new InitializeSynchronizerTrigger(
          dsoStore,
          participantAdminConnection,
          triggerContext,
          extraSynchronizerNodes,
          upgradesConfig,
        )
      )
    }
  }

  def registerTrafficReconciliationTriggers(): Unit = {
    registerTrigger(
      new ReconcileSequencerLimitWithMemberTrafficTrigger(
        triggerContext,
        dsoStore,
        localSynchronizerNode.map(_.sequencerAdminConnection),
        extraSynchronizerNodes,
        config.trafficBalanceReconciliationDelay,
      )
    )
    registerTrigger(
      new SvOnboardingUnlimitedTrafficTrigger(
        onboardingTriggerContext,
        dsoStore,
        localSynchronizerNode.map(_.sequencerAdminConnection),
        extraSynchronizerNodes,
        config.trafficBalanceReconciliationDelay,
      )
    )
  }

  def registerPostUnlimitedTrafficTriggers(): Unit = {
    registerTrigger(new SummarizingMiningRoundTrigger(triggerContext, dsoStore, connection))
    registerTrigger(
      new ReceiveSvRewardCouponTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
        connection,
        config.extraBeneficiaries,
      )
    )
    if (config.automation.enableClosedRoundArchival)
      registerTrigger(new ArchiveClosedMiningRoundsTrigger(triggerContext, dsoStore, connection))

    if (config.automation.enableDsoDelegateReplacementTrigger) {
      registerTrigger(new ElectionRequestTrigger(triggerContext, dsoStore, connection))
    }

    registerTrigger(restartDsoDelegateBasedAutomationTrigger)

    registerTrigger(new AssignTrigger(triggerContext, dsoStore, connection, store.key.dsoParty))
    registerTrigger(
      new AnsSubscriptionInitialPaymentTrigger(
        triggerContext,
        dsoStore,
        spliceInstanceNamesConfig,
        connection,
      )
    )
    registerTrigger(
      new SvPackageVettingTrigger(
        participantAdminConnection,
        dsoStore,
        config.prevetDuration,
        triggerContext,
      )
    )

    // SV status report triggers
    registerTrigger(
      new SubmitSvStatusReportTrigger(
        config,
        triggerContext,
        dsoStore,
        connection,
        cometBft,
        localSynchronizerNode.map(_.mediatorAdminConnection),
        extraSynchronizerNodes,
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
        connection,
      )
    )

    registerTrigger(
      new ExternalPartyAmuletRulesTrigger(
        triggerContext,
        dsoStore,
        connection,
      )
    )

    config.scan.foreach { scan =>
      registerTrigger(
        new PublishScanConfigTrigger(
          triggerContext,
          dsoStore,
          connection,
          scan,
          upgradesConfig,
        )
      )
    }
  }

  private val localSequencerClientContext: Option[LocalSequencerClientContext] =
    localSynchronizerNode.map(cfg =>
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

  localSequencerClientContext.flatMap(_.internalClientConfig).foreach { internalClientConfig =>
    registerTrigger(
      new LocalSequencerConnectionsTrigger(
        triggerContext,
        participantAdminConnection,
        internalClientConfig.decentralizedSynchronizerAlias,
        dsoStore,
        internalClientConfig.sequencerInternalConfig,
        config.domainMigrationId,
      )
    )
  }

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
      decentralizedSynchronizerAlias: DomainAlias,
  )

  private[automation] def bootstrapPackageIdResolver(
      initialPackageConfig: Option[InitialPackageConfig]
  )(template: QualifiedName): Option[String] =
    template.moduleName match {
      // DsoBootstrap is how we create AmuletRules in the first place so we cannot infer the package id for that from AmuletRules.
      // We could infer it from initialPackageConfig
      case "Splice.DsoBootstrap" =>
        initialPackageConfig
          .flatMap(config =>
            DarResources.dsoGovernance.getPackageIdWithVersion(config.dsoGovernanceVersion)
          )
          .orElse(
            Some(DarResources.dsoGovernance.bootstrap.packageId)
          )
      case _ => None
    }

  // defined because some triggers are registered later by
  // registerPostOnboardingTriggers
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    SpliceAppAutomationService.expectedTriggerClasses ++ Seq(
      aTrigger[SummarizingMiningRoundTrigger],
      aTrigger[SvOnboardingRequestTrigger],
      aTrigger[ReceiveSvRewardCouponTrigger],
      aTrigger[ArchiveClosedMiningRoundsTrigger],
      aTrigger[ElectionRequestTrigger],
      aTrigger[RestartDsoDelegateBasedAutomationTrigger],
      aTrigger[AmuletConfigReassignmentTrigger],
      aTrigger[AssignTrigger],
      aTrigger[TransferFollowTrigger],
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
      aTrigger[ReconcileDynamicDomainParametersTrigger],
      aTrigger[TransferCommandCounterTrigger],
      aTrigger[ExternalPartyAmuletRulesTrigger],
      aTrigger[SignSynchronizerBootstrappingStateTrigger],
      aTrigger[InitializeSynchronizerTrigger],
    )
}
