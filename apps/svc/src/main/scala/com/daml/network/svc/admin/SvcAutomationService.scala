package com.daml.network.svc.admin

import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.environment.CoinLedgerClient
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC.CoinRules.CoinRulesRequest
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an SVC app.
  *
  * Currently, this class only contains the automation that automatically accepts `CoinRulesRequests` of validators.
  */
class SvcAutomationService(
    svcParty: PartyId,
    ledgerClient: CoinLedgerClient,
    loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends LedgerAutomationServiceOrchestrator(loggerFactory)(
      ec,
      tracer,
    ) {
  override protected def timeouts: ProcessingTimeout = processingTimeouts

  override def readAs: PartyId = svcParty

  val (coinRulesRequestSubscription, coinRulesRequestAcceptanceService) =
    createService("svcAcceptCoinRulesRequestsService", ledgerClient, Seq(CoinRulesRequest.id)) {
      connection =>
        new CoinRulesRequestAcceptanceService(svcParty, connection, loggerFactory)
    }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq[AsyncOrSyncCloseable](
    SyncCloseable(
      "SVC automation services",
      Lifecycle.close(
        coinRulesRequestSubscription,
        coinRulesRequestAcceptanceService,
      )(logger),
    )
  )
}
