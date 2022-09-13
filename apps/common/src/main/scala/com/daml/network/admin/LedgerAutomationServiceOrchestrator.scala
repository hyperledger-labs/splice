package com.daml.network.admin

import com.daml.ledger.api.v1.ledger_offset.LedgerOffset
import com.daml.ledger.client.binding
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinLedgerSubscription}
import com.digitalasset.canton.lifecycle.{FlagCloseableAsync, Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{NoTracing, Spanning}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

/** Orchestrates the background (Ledger API) automations that run on a given CN node.
  *
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

  case class ServiceWithSubscriptions[S <: LedgerAutomationService](
      subscriptions: Map[PartyId, CoinLedgerSubscription],
      service: S,
      serviceName: String,
      connection: CoinLedgerConnection,
  ) extends AutoCloseable {
    override def close(): Unit = Lifecycle.close(
      (subscriptions.values.toSeq :+ service): _*
    )(logger)
  }

  protected def createService[S <: LedgerAutomationService](
      serviceName: String,
      ledgerClient: CoinLedgerClient,
      readAs: Seq[PartyId],
  )(createService: CoinLedgerConnection => S): ServiceWithSubscriptions[S] = {
    logger.debug(s"Creating service $serviceName with parties $readAs")
    val connection = ledgerClient.connection(serviceName)
    val offset = LedgerOffset().withBoundary(LedgerOffset.LedgerBoundary.LEDGER_BEGIN)
    val service = createService(connection)
    val subscriptions = readAs
      .map(party => {
        val subscription =
          startSubscriptionForParty(
            connection,
            serviceName,
            offset,
            party,
            service.templateIds,
            service,
          )
        party -> subscription
      })
      .toMap

    ServiceWithSubscriptions(subscriptions, service, serviceName, connection)
  }

  protected def updateReadAs[S <: LedgerAutomationService](
      service: ServiceWithSubscriptions[S],
      readAs: Seq[PartyId],
  ): ServiceWithSubscriptions[S] = {
    logger.debug(s"Updating service ${service.serviceName} with parties $readAs")
    (service.subscriptions.keySet -- readAs).foreach(p => service.subscriptions(p).close())
    val offset = LedgerOffset().withBoundary(LedgerOffset.LedgerBoundary.LEDGER_BEGIN)
    val subscriptions = readAs
      .map(party =>
        party -> service.subscriptions.getOrElse(
          party,
          startSubscriptionForParty(
            service.connection,
            service.serviceName,
            offset,
            party,
            service.service.templateIds,
            service.service,
          ),
        )
      )
      .toMap

    ServiceWithSubscriptions(
      subscriptions,
      service.service,
      service.serviceName,
      service.connection,
    )
  }

  private def startSubscriptionForParty(
      connection: CoinLedgerConnection,
      serviceName: String,
      offset: LedgerOffset,
      party: PartyId,
      templateIds: Seq[binding.Primitive.TemplateId[_]],
      service: LedgerAutomationService,
  ): CoinLedgerSubscription = {
    logger.debug(s"Starting subscription for service $serviceName and party $party")
    val subscription = connection.subscribeAsync(
      subscriptionName = serviceName,
      offset,
      filter = CoinLedgerConnection.transactionFilterByParty(Map(party -> templateIds)),
    )(tx =>
      withSpan(s"$serviceName.processTransaction") { implicit traceContext => _ =>
        service.processTransaction(tx)
      }
    )

    subscription.completed onComplete {
      case Success(_) =>
        logger.debug(
          s"ledger subscription for $serviceName [$service] ($party) has completed normally"
        )
      case Failure(ex) =>
        logger.warn(
          s"ledger subscription for $serviceName [$service] ($party) has completed with error",
          ex,
        )
    }

    subscription
  }
}
