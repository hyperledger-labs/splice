package com.daml.network.svc.admin

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.ledger_offset.LedgerOffset
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.environment.{CoinLedgerConnection, CoinLedgerSubscription}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  Lifecycle,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{NoTracing, Spanning, TracerProvider}
import com.digitalasset.network.CC.CoinRules.CoinRulesRequest
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

/** Manages background automation that runs on an SVC node.
  *
  * Each automation workflow is an independent application with their own ledger connection.
  * Currently, this class only contains the automation that automatically accepts `CoinRulesRequests` of validators.
  *
  * Modelled after Canton's [[com.digitalasset.canton.participant.admin.AdminWorkflowServices]].
  */
class SvcAutomationService(
    svcParty: PartyId,
    remoteParticipant: RemoteParticipantConfig,
    protected val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    actorSystem: ActorSystem,
    tracer: Tracer,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends FlagCloseableAsync
    with NamedLogging
    with Spanning
    with NoTracing {
  override protected def timeouts: ProcessingTimeout = processingTimeouts

  val (coinRulesRequestSubscription, coinRulesRequestAcceptanceService) =
    createService("svc-accept-coinrulesrequests-service") { connection =>
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

  private def createConnection(
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
    (timeouts.unbounded.await()(connection.ledgerEnd), connection)
  }

  private def createService[S <: LedgerAutomationService](
      applicationId: String
  )(createService: CoinLedgerConnection => S): (CoinLedgerSubscription, S) = {
    val (offset, connection) = createConnection(applicationId, applicationId)
    val service = createService(connection)
    val subscription = connection.subscribeAsync(
      subscriptionName = applicationId,
      offset,
      filter = CoinLedgerConnection.transactionFilter(svcParty, CoinRulesRequest.id),
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
