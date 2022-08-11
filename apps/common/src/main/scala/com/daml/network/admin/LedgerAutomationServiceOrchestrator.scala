package com.daml.network.admin

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.ledger_offset.LedgerOffset
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.environment.{CoinLedgerConnection, CoinLedgerSubscription}
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{NoTracing, Spanning, TracerProvider}
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
    remoteParticipant: RemoteParticipantConfig,
    protected val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
)(implicit
    ec: ExecutionContextExecutor,
    actorSystem: ActorSystem,
    tracer: Tracer,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends FlagCloseableAsync
    with NamedLogging
    with NoTracing
    with Spanning {

  def readAs: PartyId

  protected def createConnection(
      applicationId: String,
      workflowId: String,
  ): (LedgerOffset, CoinLedgerConnection) = {
    val appId = ApiTypes.ApplicationId(applicationId)
    val connection = CoinLedgerConnection(
      remoteParticipant.ledgerApi,
      appId,
      maxRetries = 10,
      ApiTypes.WorkflowId(workflowId),
      CommandClientConfiguration.default,
      remoteParticipant.token,
      timeouts,
      loggerFactory,
      tracerProvider,
    )
    (timeouts.network.await()(connection.ledgerEnd), connection)
  }

  protected def createService[S <: LedgerAutomationService](
      applicationId: String
  )(createService: CoinLedgerConnection => S): (CoinLedgerSubscription, S) = {
    val (offset, connection) = createConnection(applicationId, applicationId)
    val service = createService(connection)
    val subscription = connection.subscribeAsync(
      subscriptionName = applicationId,
      offset,
      filter = CoinLedgerConnection.transactionFilter(readAs, CoinRulesRequest.id),
    )(tx =>
      withSpan(s"$applicationId.processTransaction") { implicit traceContext => _ =>
        service.processTransaction(tx)
      }
    )

    subscription.completed onComplete {
      case Success(_) =>
        logger.debug(
          s"ledger subscription for SVC automation service [$service] has completed normally"
        )
      case Failure(ex) =>
        logger.warn(
          s"ledger subscription for SVC automation service [$service] has completed with error",
          ex,
        )
    }

    (subscription, service)
  }
}
