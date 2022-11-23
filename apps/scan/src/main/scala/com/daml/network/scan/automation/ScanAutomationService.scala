package com.daml.network.scan.automation

import akka.stream.Materializer
import com.daml.network.automation.{AutomationService, AuditLogIngestionService}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.ReferenceDataIngestionSink
import com.daml.network.scan.store.{CoinTransactionsIngestionSink, ScanCCHistoryStore}
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on a CC Scan app. */
class ScanAutomationService(
    svcParty: PartyId,
    ledgerClient: CoinLedgerClient,
    clockConfig: ClockConfig,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
    protected val timeouts: ProcessingTimeout,
    store: ScanCCHistoryStore,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(clockConfig, retryProvider) {

  private val connection = registerResource(ledgerClient.connection("ScanAutomationService"))

  registerService(
    new AuditLogIngestionService(
      "Scan:ReadCoinTransactionsService",
      new CoinTransactionsIngestionSink(svcParty, connection, store, loggerFactory),
      connection,
      loggerFactory,
      timeouts,
    )
  )

  registerService(
    new AuditLogIngestionService(
      "Scan:ReadReferenceData",
      new ReferenceDataIngestionSink(svcParty, store, loggerFactory),
      connection,
      loggerFactory,
      timeouts,
    )
  )
}
