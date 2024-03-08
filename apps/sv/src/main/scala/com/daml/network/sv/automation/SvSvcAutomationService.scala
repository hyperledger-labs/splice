package com.daml.network.sv.automation

import com.daml.network.automation.AutomationServiceCompanion.{TriggerClass, aTrigger}
import com.daml.network.automation.{
  AssignTrigger,
  AutomationServiceCompanion,
  CNNodeAppAutomationService,
  TransferFollowTrigger,
}
import com.daml.network.environment.*
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.automation.SvSvcAutomationService.{
  LocalSequencerClientConfig,
  LocalSequencerClientContext,
}
import com.daml.network.sv.automation.confirmation.*
import com.daml.network.sv.automation.singlesv.*
import com.daml.network.sv.automation.singlesv.membership.SvNamespaceMembershipTrigger
import com.daml.network.sv.automation.singlesv.membership.offboarding.{
  SvOffboardingPartyToParticipantProposalTrigger,
  SvOffboardingSequencerTrigger,
}
import com.daml.network.sv.automation.singlesv.membership.onboarding.*
import com.daml.network.sv.automation.singlesv.offboarding.SvOffboardingMediatorTrigger
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SequencerPruningConfig, SvAppBackendConfig}
import com.daml.network.sv.migration.GlobalDomainMigrationTrigger
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
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

class SvSvcAutomationService(
    clock: Clock,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
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
      svcStore,
      PackageIdResolver
        .inferFromCoinRules(
          clock,
          svcStore,
          loggerFactory,
          SvSvcAutomationService.bootstrapPackageIdResolver,
        ),
      ledgerClient,
      retryProvider,
      config.ingestFromParticipantBegin,
    ) {

  override def companion = SvSvcAutomationService

  private[network] val restartLeaderBasedAutomationTrigger =
    new RestartLeaderBasedAutomationTrigger(
      triggerContext,
      svcStore,
      connection,
      clock,
      config,
      retryProvider,
    )

  // Triggers that require namespace permissions and the existence of the SvcRules and CoinRules contracts
  def registerPostOnboardingTriggers(): Unit = {
    registerTrigger(new SummarizingMiningRoundTrigger(triggerContext, svcStore, connection))
    registerTrigger(
      new SvOnboardingRequestTrigger(triggerContext, svcStore, svStore, config, connection)
    )
    if (config.automation.enableSvRewards) {
      if (config.automation.useNewSvRewardIssuance) {
        registerTrigger(
          new ReceiveSvRewardCouponTrigger(
            triggerContext,
            svcStore,
            connection,
            config.extraBeneficiaries,
          )
        )
      } else {
        registerTrigger(new SvRewardTrigger(triggerContext, svcStore, connection))
      }
    }
    if (config.automation.enableClosedRoundArchival)
      registerTrigger(new ArchiveClosedMiningRoundsTrigger(triggerContext, svcStore, connection))

    if (config.automation.enableLeaderReplacementTrigger) {
      registerTrigger(new ElectionRequestTrigger(triggerContext, svcStore, connection))
    }

    registerTrigger(restartLeaderBasedAutomationTrigger)

    registerTrigger(new SvcRulesTransferTrigger(triggerContext, svcStore, connection))
    registerTrigger(new AssignTrigger(triggerContext, svcStore, connection, store.key.svcParty))

    registerTrigger(
      new TransferFollowTrigger(
        triggerContext,
        svcStore,
        connection,
        store.key.svcParty,
        implicit tc =>
          svcStore.listSvcRulesTransferFollowers(participantAdminConnection).flatMap {
            svcRulesFollowers =>
              // don't try to schedule CoinRules' followers if CoinRules might move
              // (i.e. be one of svcRulesFollowers)
              if (svcRulesFollowers.nonEmpty) Future successful svcRulesFollowers
              else svcStore.listCoinRulesTransferFollowers(participantAdminConnection)
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
          svcStore
            .lookupSvcRules()
            .flatMap(
              _.map(svStore.listSvcRulesTransferFollowers(_, participantAdminConnection))
                .getOrElse(Future successful Seq.empty)
            ),
      )
    )
    registerTrigger(
      new CnsSubscriptionInitialPaymentTrigger(triggerContext, svcStore, connection)
    )
    registerTrigger(
      new SvPackageVettingTrigger(
        participantAdminConnection,
        svcStore,
        config.prevetDuration,
        triggerContext,
      )
    )

    // SV status report triggers
    registerTrigger(
      new SubmitSvStatusReportTrigger(
        config,
        triggerContext,
        svcStore,
        connection,
        cometBft,
        localDomainNode.map(_.mediatorAdminConnection),
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvStatusReportMetricsExportTrigger(
        triggerContext,
        svcStore,
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
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOffboardingMediatorTrigger(
        wallClockTriggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingUnlimitedTrafficTrigger(
        onboardingTriggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOffboardingSequencerTrigger(
        wallClockTriggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new ReconcileSequencerLimitWithMemberTrafficTrigger(
        triggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvNamespaceMembershipTrigger(
        onboardingTriggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingPromoteParticipantToSubmitterTrigger(
        onboardingTriggerContext,
        svcStore,
        participantAdminConnection,
        config.enableOnboardingParticipantPromotionDelay,
      )
    )
    registerTrigger(
      new SvOnboardingPartyToParticipantProposalTrigger(
        onboardingTriggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingSequencerProposalTrigger(
        onboardingTriggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingMediatorProposalTrigger(
        onboardingTriggerContext,
        svcStore,
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
            svcStore,
            participantAdminConnection,
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
            svcStore,
            connection,
            node,
          )
        )
        registerTrigger(
          new ReconcileCometBftNetworkConfigWithSvcRulesTrigger(
            triggerContext,
            svcStore,
            node,
          )
        )
      }
    }

    config.scan.foreach { scan =>
      registerTrigger(
        new PublishScanConfigTrigger(
          triggerContext,
          svcStore,
          connection,
          scan,
        )
      )
    }

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
        svcStore,
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
          svcStore,
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

object SvSvcAutomationService extends AutomationServiceCompanion {
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
      // SvcBootstrap is how we create CoinRules in the first place so we cannot infer the package id for that from CoinRules.
      case "CN.SvcBootstrap" =>
        Some(DarResources.svcGovernance.bootstrap.packageId)
      // ImportCrates are created before CoinRules. Given that this is only a hack until we have upgrading
      // we can hardcode this.
      case "CC.CoinImport" =>
        Some(DarResources.cantonCoin.bootstrap.packageId)
      case _ => None
    }

  // defined because some triggers are registered later by
  // registerPostOnboardingTriggers
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    CNNodeAppAutomationService.expectedTriggerClasses ++ Seq(
      aTrigger[SummarizingMiningRoundTrigger],
      aTrigger[SvOnboardingRequestTrigger],
      aTrigger[SvRewardTrigger],
      aTrigger[ReceiveSvRewardCouponTrigger],
      aTrigger[ArchiveClosedMiningRoundsTrigger],
      aTrigger[ElectionRequestTrigger],
      aTrigger[RestartLeaderBasedAutomationTrigger],
      aTrigger[SvcRulesTransferTrigger],
      aTrigger[AssignTrigger],
      aTrigger[TransferFollowTrigger],
      aTrigger[CnsSubscriptionInitialPaymentTrigger],
      aTrigger[SvPackageVettingTrigger],
      aTrigger[SvOffboardingPartyToParticipantProposalTrigger],
      aTrigger[SvOffboardingMediatorTrigger],
      aTrigger[SvOnboardingUnlimitedTrafficTrigger],
      aTrigger[SvOffboardingSequencerTrigger],
      aTrigger[ReconcileSequencerLimitWithMemberTrafficTrigger],
      aTrigger[SvNamespaceMembershipTrigger],
      aTrigger[SvOnboardingPromoteParticipantToSubmitterTrigger],
      aTrigger[SvOnboardingPartyToParticipantProposalTrigger],
      aTrigger[SvOnboardingSequencerProposalTrigger],
      aTrigger[SvOnboardingMediatorProposalTrigger],
      aTrigger[GlobalDomainMigrationTrigger],
      aTrigger[PublishLocalCometBftNodeConfigTrigger],
      aTrigger[PublishScanConfigTrigger],
      aTrigger[ReconcileCometBftNetworkConfigWithSvcRulesTrigger],
      aTrigger[LocalSequencerConnectionsTrigger],
      aTrigger[SequencerPruningTrigger],
      aTrigger[SubmitSvStatusReportTrigger],
      aTrigger[SvStatusReportMetricsExportTrigger],
    )
}
