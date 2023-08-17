package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  CNNodeAppAutomationService,
  TransferFollowTrigger,
  AssignTrigger,
}
import com.daml.network.environment.{CNLedgerClient, ParticipantAdminConnection, RetryProvider}
import com.daml.network.sv.automation.confirmation.{
  ArchiveClosedMiningRoundsTrigger,
  ElectionRequestTrigger,
  SummarizingMiningRoundTrigger,
  SvOnboardingRequestTrigger,
}
import com.daml.network.sv.automation.singlesv.*
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

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
        new PublishLocalCometBftNodeConfigTrigger(triggerContext, svcStore, connection, node)
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

  if (config.automation.useMemberTrafficInsteadOfValidatorTraffic)
    registerTrigger(
      new ReconcileSequencerLimitWithMemberTrafficTrigger(
        triggerContext,
        svcStore,
        participantAdminConnection,
      )
    )
  else
    registerTrigger(
      new ReconcileSequencerTrafficLimitWithPurchasedTrafficTrigger(
        triggerContext,
        svcStore,
        participantAdminConnection,
      )
    )

  if (config.automation.enableLeaderReplacement) {
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
        svcStore.listSvcRulesTransferFollowers().flatMap { svcRulesFollowers =>
          // don't try to schedule CoinRules' followers if CoinRules might move
          // (i.e. be one of svcRulesFollowers)
          if (svcRulesFollowers.nonEmpty) Future successful svcRulesFollowers
          else svcStore.listCoinRulesTransferFollowers()
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
            _.map(svStore.listSvcRulesTransferFollowers(_)).getOrElse(Future successful Seq.empty)
          ),
    )
  )

}
