package com.daml.network.scan.automation

import com.daml.network.automation.{
  AcsIngestionService,
  AuditLogIngestionService,
  AutomationService,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.store.{CoinTransactionsIngestionSink, ScanStore}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on a CC Scan app. */
class ScanAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    svcParty: PartyId,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
    protected val timeouts: ProcessingTimeout,
    store: ScanStore,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends AutomationService(automationConfig, clock, retryProvider) {

  private val connection = registerResource(ledgerClient.connection("ScanAutomationService"))

  registerService(
    new AuditLogIngestionService(
      "Scan:ReadCoinTransactionsService",
      new CoinTransactionsIngestionSink(svcParty, store.history, loggerFactory),
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

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
}
