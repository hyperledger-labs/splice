package com.daml.network.svc.admin

import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Background automation to collect audit log entries, which currently consists of only
  * the collection of all transfers results, for summary when closing the round.
  */
// TODO(M1-52): integrate log collection into our store infrastructure as part of the work on automating rewards issuance
class SvcLogCollectionService(
    svcParty: PartyId,
    ledgerClient: CoinLedgerClient,
    loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
    store: SvcStore,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends LedgerAutomationServiceOrchestrator(loggerFactory)(
      ec,
      tracer,
    ) {
  override protected def timeouts: ProcessingTimeout = processingTimeouts

  val roundSummaryCollection =
    createService("svcRoundSummaryCollectionService", ledgerClient, Seq(svcParty)) { connection =>
      new RoundSummaryCollectionService(svcParty, connection, store.events, loggerFactory)
    }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq[AsyncOrSyncCloseable](
    SyncCloseable(
      "SVC automation services",
      Lifecycle.close(roundSummaryCollection)(logger),
    )
  )
}
