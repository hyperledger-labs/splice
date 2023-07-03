package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{CNNodeAppAutomationService, TransferInTrigger}
import com.daml.network.environment.{CNLedgerClient, ParticipantAdminConnection, RetryProvider}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.ExpiringLock
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

class SvSvcAutomationService(
    clock: Clock,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    cometBft: Option[CometBftNode],
    globalLockO: Option[ExpiringLock],
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

  registerTrigger(new SummarizingMiningRoundTrigger(triggerContext, svcStore, connection))
  registerTrigger(new SvOnboardingRequestTrigger(triggerContext, svcStore, svStore, connection))
  if (config.automation.enableSvRewards) {
    registerTrigger(new SvRewardTrigger(triggerContext, svcStore, connection))
  }
  registerTrigger(new ArchiveClosedMiningRoundsTrigger(triggerContext, svcStore, connection))

  // Register optional BFT triggers
  cometBft.foreach { node =>
    if (node.cometBftConfig.automationEnabled) {
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

  globalLockO.foreach(lock =>
    registerTrigger(
      new ReconcileSequencerTrafficLimitWithPurchasedTrafficTrigger(
        triggerContext,
        svcStore,
        participantAdminConnection,
        lock,
      )
    )
  )
  registerTrigger(new ElectionRequestTrigger(triggerContext, svcStore, connection))

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
  registerTrigger(new TransferInTrigger(triggerContext, svcStore, connection, store.key.svcParty))
}
