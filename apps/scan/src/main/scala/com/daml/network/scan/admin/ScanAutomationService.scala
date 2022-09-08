package com.daml.network.scan.admin

import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.store.ScanCCHistoryStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.CC.Coin.{Coin, LockedCoin}
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

  override def readAs: PartyId = svcParty

  val (coinFlatStreamSubscription, readCcTransfersService) =
    // TODO(Arne): the subscription here should read from ledger start
    createService("Scan:ReadCoinTransactionsService", ledgerClient, Seq(Coin.id, LockedCoin.id)) {
      connection =>
        new ReadCoinTransactionsService(svcParty, connection, store, loggerFactory)
    }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq[AsyncOrSyncCloseable](
    SyncCloseable(
      "SVC automation services",
      Lifecycle.close(
        coinFlatStreamSubscription,
        readCcTransfersService,
      )(logger),
    )
  )
}
