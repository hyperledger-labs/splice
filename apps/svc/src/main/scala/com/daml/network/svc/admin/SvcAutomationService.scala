package com.daml.network.svc.admin

import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.svc.store.SvcAppStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an SVC app.
  *
  * Currently, this class only contains:
  * - the automation that automatically accepts `CoinRulesRequests` of validators.
  * - collection of all transfers, for summary when closing the round.
  */
class SvcAutomationService(
    svcParty: PartyId,
    ledgerClient: CoinLedgerClient,
    loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
    store: SvcAppStore,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends LedgerAutomationServiceOrchestrator(loggerFactory)(
      ec,
      tracer,
    ) {
  override protected def timeouts: ProcessingTimeout = processingTimeouts

  val coinRulesRequestAcceptance =
    createService("svcAcceptCoinRulesRequestsService", ledgerClient, Seq(svcParty)) { connection =>
      new CoinRulesRequestAcceptanceService(svcParty, connection, loggerFactory)
    }

  val roundSummaryCollection =
    createService("svcRoundSummaryCollectionService", ledgerClient, Seq(svcParty)) { connection =>
      new RoundSummaryCollectionService(svcParty, connection, store, loggerFactory)
    }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq[AsyncOrSyncCloseable](
    SyncCloseable(
      "SVC automation services",
      Lifecycle.close(coinRulesRequestAcceptance, roundSummaryCollection)(logger),
    )
  )
}
