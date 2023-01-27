package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.CoinAppAutomationService
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.svc.config.SvcAppBackendConfig
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvcAutomationService(
    clock: Clock,
    config: SvcAppBackendConfig,
    store: SvcStore,
    ledgerClient: CoinLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CoinAppAutomationService(
      config.automation,
      clock,
      store,
      ledgerClient,
      participantAdminConnection,
      retryProvider,
    ) {

  registerTrigger(
    new AdvanceOpenMiningRoundTrigger(triggerContext, config, store, connection)
  )
  registerTrigger(new ExpireIssuingMiningRoundTrigger(triggerContext, store, connection))
  registerTrigger(new SummarizingMiningRoundTrigger(triggerContext, store, connection))
  registerTrigger(new ClosedMiningRoundTrigger(triggerContext, store, connection))
  registerTrigger(new ExpiredCoinTrigger(triggerContext, store, connection))
  registerTrigger(new ExpiredLockedCoinTrigger(triggerContext, store, connection))
}
