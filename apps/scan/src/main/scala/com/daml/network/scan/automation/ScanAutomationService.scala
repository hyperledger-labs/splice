package com.daml.network.scan.automation

import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.{ReadCoinTransactionsService, ReadReferenceDataService}
import com.daml.network.scan.store.ScanCCHistoryStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on a CC Scan app.
  */
class ScanAutomationService(
    svcParty: PartyId,
    ledgerClient: CoinLedgerClient,
    loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
    store: ScanCCHistoryStore,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends LedgerAutomationServiceOrchestrator(loggerFactory)(
      ec,
      tracer,
    ) {
  override protected def timeouts: ProcessingTimeout = processingTimeouts

  val readCoinTransactions =
    createService("Scan:ReadCoinTransactionsService", ledgerClient, Seq(svcParty)) { connection =>
      new ReadCoinTransactionsService(svcParty, connection, store, loggerFactory)
    }

  val readReferenceData =
    createService("Scan:ReadReferenceData", ledgerClient, Seq(svcParty)) { connection =>
      new ReadReferenceDataService(svcParty, connection, store, loggerFactory)
    }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq[AsyncOrSyncCloseable](
    SyncCloseable(
      "Scan automation services",
      Lifecycle.close(readCoinTransactions, readReferenceData)(logger),
    )
  )
}
