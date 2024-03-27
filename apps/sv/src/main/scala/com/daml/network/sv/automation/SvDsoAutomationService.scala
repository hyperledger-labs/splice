package com.daml.network.sv.automation

import com.daml.network.automation.{
  AssignTrigger,
  AutomationServiceCompanion,
  CNNodeAppAutomationService,
  TransferFollowTrigger,
}
import com.daml.network.automation.AutomationServiceCompanion.{aTrigger, TriggerClass}
import com.daml.network.environment.*
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.automation.SvDsoAutomationService.{
  LocalSequencerClientConfig,
  LocalSequencerClientContext,
}
import com.daml.network.sv.automation.confirmation.*
import com.daml.network.sv.automation.singlesv.*
import com.daml.network.sv.automation.singlesv.membership.SvNamespaceMembershipTrigger
import com.daml.network.sv.automation.singlesv.membership.offboarding.{
  SvOffboardingMediatorTrigger,
  SvOffboardingPartyToParticipantProposalTrigger,
  SvOffboardingSequencerTrigger,
}
import com.daml.network.sv.automation.singlesv.membership.onboarding.*
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SequencerPruningConfig, SvAppBackendConfig}
import com.daml.network.sv.migration.GlobalDomainMigrationTrigger
import com.daml.network.sv.store.{SvDsoStore, SvSvStore}
import com.daml.network.util.{QualifiedName, TemplateJsonDecoder}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.{Clock, WallClock}
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer

import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}

class SvDsoAutomationService(
    clock: Clock,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    dsoStore: SvDsoStore,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    cometBft: Option[CometBftNode],
    localDomainNode: Option[LocalDomainNode],
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateJsonDecoder: TemplateJsonDecoder,
) extends CNNodeAppAutomationService(
      config.automation,
      clock,
      dsoStore,
      PackageIdResolver
        .inferFromAmuletRules(
          clock,
          dsoStore,
          loggerFactory,
          SvDsoAutomationService.bootstrapPackageIdResolver,
        ),
      ledgerClient,
      retryProvider,
      config.ingestFromParticipantBegin,
    ) {

  override def companion = SvDsoAutomationService

  private[network] val restartLeaderBasedAutomationTrigger =
    new RestartLeaderBasedAutomationTrigger(
      triggerContext,
      dsoStore,
      connection,
      clock,
      config,
      retryProvider,
    )

  // Triggers that require namespace permissions and the existence of the DsoRules and AmuletRules contracts
  def registerPostOnboardingTriggers(): Unit = {
    registerTrigger(new SummarizingMiningRoundTrigger(triggerContext, dsoStore, connection))
    registerTrigger(
      new SvOnboardingRequestTrigger(triggerContext, dsoStore, svStore, config, connection)
    )
    registerTrigger(
      new ReceiveSvRewardCouponTrigger(
        triggerContext,
        dsoStore,
        connection,
        config.extraBeneficiaries,
      )
    )
    if (config.automation.enableClosedRoundArchival)
      registerTrigger(new ArchiveClosedMiningRoundsTrigger(triggerContext, dsoStore, connection))

    if (config.automation.enableLeaderReplacementTrigger) {
      registerTrigger(new ElectionRequestTrigger(triggerContext, dsoStore, connection))
    }

    registerTrigger(restartLeaderBasedAutomationTrigger)

    registerTrigger(new DsoRulesTransferTrigger(triggerContext, dsoStore, connection))
    registerTrigger(new AssignTrigger(triggerContext, dsoStore, connection, store.key.dsoParty))

    registerTrigger(
      new TransferFollowTrigger(
        triggerContext,
        dsoStore,
        connection,
        store.key.dsoParty,
        implicit tc =>
          dsoStore.listDsoRulesTransferFollowers().flatMap { dsoRulesFollowers =>
            // don't try to schedule AmuletRules' followers if AmuletRules might move
            // (i.e. be one of dsoRulesFollowers)
            if (dsoRulesFollowers.nonEmpty) Future successful dsoRulesFollowers
            else dsoStore.listAmuletRulesTransferFollowers()
          },
      )
    )

    registerTrigger(
      new TransferFollowTrigger(
        triggerContext,
        svStore,
        connection,
        store.key.svParty,
        implicit tc =>
          dsoStore
            .lookupDsoRules()
            .flatMap(
              _.map(svStore.listDsoRulesTransferFollowers(_))
                .getOrElse(Future successful Seq.empty)
            ),
      )
    )
    registerTrigger(
      new AnsSubscriptionInitialPaymentTrigger(triggerContext, dsoStore, connection)
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
        localDomainNode.map(_.mediatorAdminConnection),
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvStatusReportMetricsExportTrigger(
        triggerContext,
        dsoStore,
      )
    )

    // required for triggers that must run in sim time as well
    val wallClockTriggerContext = triggerContext
      .focus(_.clock)
      .replace(
        new WallClock(triggerContext.timeouts, triggerContext.loggerFactory)
      )

    val onboardingTriggerContext = wallClockTriggerContext
      .focus(_.config.pollingInterval)
      .replace(
        config.onboardingPollingInterval.getOrElse(wallClockTriggerContext.config.pollingInterval)
      )
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
      new SvOnboardingUnlimitedTrafficTrigger(
        onboardingTriggerContext,
        dsoStore,
        localDomainNode.map(_.sequencerAdminConnection),
        config.trafficBalanceReconciliationDelay,
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
      new ReconcileSequencerLimitWithMemberTrafficTrigger(
        triggerContext,
        dsoStore,
        localDomainNode.map(_.sequencerAdminConnection),
        config.trafficBalanceReconciliationDelay,
      )
    )
    registerTrigger(
      new SvNamespaceMembershipTrigger(
        onboardingTriggerContext,
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

    (localDomainNode, config.domainMigrationDumpPath) match {
      case (Some(domainNode), Some(dumpPath)) =>
        registerTrigger(
          new GlobalDomainMigrationTrigger(
            config.domainMigrationId,
            triggerContext,
            config.domains.global.alias,
            domainNode,
            dsoStore,
            participantAdminConnection,
            domainNode.sequencerAdminConnection,
            dumpPath: Path,
          )
        )
      case _ => ()
    }
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

    config.scan.foreach { scan =>
      registerTrigger(
        new PublishScanConfigTrigger(
          triggerContext,
          dsoStore,
          connection,
          scan,
        )
      )
    }

    registerTrigger(
      new ReconcileDomainFeesConfigTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
  }

  private val localSequencerClientContext: Option[LocalSequencerClientContext] =
    localDomainNode.map(cfg =>
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
        internalClientConfig.globalDomainAlias,
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
      globalDomainAlias: DomainAlias,
  )

  private[automation] def bootstrapPackageIdResolver(template: QualifiedName): Option[String] =
    template.moduleName match {
      // DsoBootstrap is how we create AmuletRules in the first place so we cannot infer the package id for that from AmuletRules.
      case "Splice.DsoBootstrap" =>
        Some(DarResources.dsoGovernance.bootstrap.packageId)
      // ImportCrates are created before AmuletRules. Given that this is only a hack until we have upgrading
      // we can hardcode this.
      case "Splice.AmuletImport" =>
        Some(DarResources.cantonAmulet.bootstrap.packageId)
      case _ => None
    }

  // defined because some triggers are registered later by
  // registerPostOnboardingTriggers
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    CNNodeAppAutomationService.expectedTriggerClasses ++ Seq(
      aTrigger[SummarizingMiningRoundTrigger],
      aTrigger[SvOnboardingRequestTrigger],
      aTrigger[ReceiveSvRewardCouponTrigger],
      aTrigger[ArchiveClosedMiningRoundsTrigger],
      aTrigger[ElectionRequestTrigger],
      aTrigger[RestartLeaderBasedAutomationTrigger],
      aTrigger[DsoRulesTransferTrigger],
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
      aTrigger[GlobalDomainMigrationTrigger],
      aTrigger[PublishLocalCometBftNodeConfigTrigger],
      aTrigger[PublishScanConfigTrigger],
      aTrigger[ReconcileCometBftNetworkConfigWithDsoRulesTrigger],
      aTrigger[LocalSequencerConnectionsTrigger],
      aTrigger[SequencerPruningTrigger],
      aTrigger[SubmitSvStatusReportTrigger],
      aTrigger[SvStatusReportMetricsExportTrigger],
      aTrigger[ReconcileDomainFeesConfigTrigger],
    )
}
