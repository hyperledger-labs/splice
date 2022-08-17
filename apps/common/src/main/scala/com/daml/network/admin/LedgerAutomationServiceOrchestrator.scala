package com.daml.network.admin

import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinLedgerSubscription}
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{NoTracing, Spanning}
import com.digitalasset.network.CC.CoinRules.CoinRulesRequest
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

/** Orchestrates the background (Ledger API) automations that run on a given CN node.
  *
  * TODO(i543): we only want one ledger connection per CN app
  * Each automation workflow is an independent application with their own ledger connection.
  *
  * Modelled after Canton's [[com.digitalasset.canton.participant.admin.AdminWorkflowServices]].
  */
abstract class LedgerAutomationServiceOrchestrator(
    protected val loggerFactory: NamedLoggerFactory
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends FlagCloseableAsync
    with NamedLogging
    with NoTracing
    with Spanning {

  def readAs: PartyId

  protected def createService[S <: LedgerAutomationService](
      serviceName: String,
      ledgerClient: CoinLedgerClient,
  )(createService: CoinLedgerConnection => S): (CoinLedgerSubscription, S) = {
    val connection = ledgerClient.connection(serviceName)
    val offset = timeouts.network.await()(connection.ledgerEnd)
    val service = createService(connection)
    val subscription = connection.subscribeAsync(
      subscriptionName = serviceName,
      offset,
      filter = CoinLedgerConnection.transactionFilter(readAs, CoinRulesRequest.id),
    )(tx =>
      withSpan(s"$serviceName.processTransaction") { implicit traceContext => _ =>
        service.processTransaction(tx)
      }
    )

    subscription.completed onComplete {
      case Success(_) =>
        logger.debug(
          s"ledger subscription for $serviceName [$service] has completed normally"
        )
      case Failure(ex) =>
        logger.warn(
          s"ledger subscription for $serviceName [$service] has completed with error",
          ex,
        )
    }

    (subscription, service)
  }
}
