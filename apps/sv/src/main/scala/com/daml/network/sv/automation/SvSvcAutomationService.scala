package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.environment.{CNLedgerClient, ParticipantAdminConnection, RetryProvider}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
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
  registerTrigger(new SvRewardTrigger(triggerContext, svcStore, connection))
  registerTrigger(new ArchiveClosedMiningRoundsTrigger(triggerContext, svcStore, connection))
  if (config.automation.enableUnclaimedRewardExpiration) {
    registerTrigger(new ExpireRewardCouponsTrigger(triggerContext, svcStore, connection))
  }
  registerTrigger(new MergeUnclaimedRewardsTrigger(triggerContext, svcStore, connection))

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

  registerTrigger(
    new ReconcileSequencerTrafficLimitWithPurchasedTrafficTrigger(
      triggerContext,
      svcStore,
      participantAdminConnection,
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
}
