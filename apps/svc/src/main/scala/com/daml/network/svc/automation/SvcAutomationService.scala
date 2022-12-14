package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  AcsIngestionService,
  AuditLogIngestionService,
  AutomationService,
}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvcAutomationService(
    clock: Clock,
    config: LocalSvcAppConfig,
    store: SvcStore,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(config.automation, clock, retryProvider) {

  private val connection = registerResource(ledgerClient.connection(this.getClass.getSimpleName))

  registerService(
    new AcsIngestionService(
      store.getClass.getSimpleName,
      store.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  registerService(
    new AuditLogIngestionService(
      "svcRoundSummaryCollectionService",
      new RoundSummaryIngestionService(
        store.svcParty,
        connection,
        store.events,
        loggerFactory,
      ),
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  registerTrigger(
    new AdvanceOpenMiningRoundTrigger(triggerContext, config, store, connection)
  )
  registerTrigger(new ExpireIssuingMiningRoundTrigger(triggerContext, store, connection))
  registerTrigger(new CoinRulesRequestTrigger(triggerContext, store, connection))
  registerTrigger(new SummarizingMiningRoundTrigger(triggerContext, store, connection))
  registerTrigger(new ClosedMiningRoundTrigger(triggerContext, store, connection))
}
