package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  AssignTrigger,
  CNNodeAppAutomationService,
  TransferFollowTrigger,
}
import com.daml.network.environment.{
  CNLedgerClient,
  DarResources,
  PackageIdResolver,
  ParticipantAdminConnection,
  RetryProvider,
}
import com.daml.network.sv.automation.confirmation.{
  ArchiveClosedMiningRoundsTrigger,
  CnsSubscriptionInitialPaymentTrigger,
  ElectionRequestTrigger,
  SummarizingMiningRoundTrigger,
  SvOnboardingRequestTrigger,
}
import com.daml.network.sv.automation.singlesv.*
import com.daml.network.sv.automation.singlesv.onboarding.{
  SvOnboardingMediatorProposalTrigger,
  SvOnboardingNamespaceProposalTrigger,
  SvOnboardingPartyToParticipantProposalTrigger,
  SvOnboardingSequencerProposalTrigger,
}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvSvcStore, SvSvStore}
import com.daml.network.util.QualifiedName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.{Clock, WallClock}
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps

import scala.concurrent.{ExecutionContext, Future}

class SvSvcAutomationService(
    clock: Clock,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    cometBft: Option[CometBftNode],
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      config.automation,
      clock,
      svcStore,
      PackageIdResolver
        .inferFromCoinRules(
          clock,
          svcStore,
          loggerFactory,
          SvSvcAutomationService.extraPackageIdResolver,
        ),
      ledgerClient,
      retryProvider,
    ) {

  config.acsStoreDump.foreach(config =>
    registerTrigger(
      new PeriodicAcsStoreBackupTrigger(
        config,
        triggerContext,
        svcStore,
      )
    )
  )

  registerTrigger(new SummarizingMiningRoundTrigger(triggerContext, svcStore, connection))
  registerTrigger(new SvOnboardingRequestTrigger(triggerContext, svcStore, svStore, connection))
  if (config.automation.enableSvRewards) {
    registerTrigger(new SvRewardTrigger(triggerContext, svcStore, connection))
  }
  if (config.automation.enableClosedRoundArchival)
    registerTrigger(new ArchiveClosedMiningRoundsTrigger(triggerContext, svcStore, connection))

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

  if (config.automation.enableLeaderReplacementTrigger) {
    registerTrigger(new ElectionRequestTrigger(triggerContext, svcStore, connection))
  }

  registerTrigger(
    new RestartLeaderBasedAutomationTrigger(
      triggerContext,
      svcStore,
      connection,
      clock,
      config,
      retryProvider,
    )
  )

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

  // requires namespace permissions to run these triggers, can only be run after onboarding
  def registerPostOnboardingTriggers(): Unit = {
    // required for triggers that must run in sim time as well
    val wallClockTriggerContext = triggerContext
      .focus(_.clock)
      .replace(
        new WallClock(triggerContext.timeouts, triggerContext.loggerFactory)
      )
      .focus(_.config.pollingInterval)
      .replace(NonNegativeFiniteDuration.ofSeconds(1))
    registerTrigger(
      new ReconcileSequencerLimitWithMemberTrafficTrigger(
        triggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingNamespaceProposalTrigger(triggerContext, svcStore, participantAdminConnection)
    )
    registerTrigger(
      new SvOnboardingPartyToParticipantProposalTrigger(
        wallClockTriggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingSequencerProposalTrigger(
        wallClockTriggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingMediatorProposalTrigger(
        wallClockTriggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
  }
}

object SvSvcAutomationService {
  private[automation] def extraPackageIdResolver(template: QualifiedName): Option[String] =
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
}
