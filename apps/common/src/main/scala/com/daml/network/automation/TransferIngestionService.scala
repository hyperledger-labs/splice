package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CoinLedgerConnection, CoinLedgerSubscription, CoinRetries}
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{
  TransferUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.store.TransferStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TransferIngestionService(
    ingestionTargetName: String,
    ingestionSink: TransferStore.IngestionSink,
    domain: DomainId,
    connection: CoinLedgerConnection,
    override protected val retryProvider: CoinRetries,
    baseLoggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends LedgerIngestionService {

  override protected val loggerFactory: NamedLoggerFactory =
    baseLoggerFactory.append("ingestionLoopFor", ingestionTargetName)

  override protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[CoinLedgerSubscription[?]] = for {
    lastIngestedOffset <- ingestionSink.getLastIngestedOffset(domain)
    subscribeFrom <- lastIngestedOffset match {
      case None => ingestInFlight(ingestionSink.ingestionFilter)
      case Some(off) => Future.successful(new LedgerOffset.Absolute(off))
    }
  } yield connection.subscribeAsync(
    this.getClass.getSimpleName,
    loggerFactory,
    subscribeFrom,
    ingestionSink.ingestionFilter,
    domain,
  ) {
    case TransactionTreeUpdate(_) => Future.unit
    case TransferUpdate(transfer) =>
      ingestionSink.ingestTransfer(transfer)
  }

  private def ingestInFlight(
      filter: PartyId
  )(implicit traceContext: TraceContext): Future[LedgerOffset.Absolute] = {
    for {
      (tfs, off) <- connection.getInFlightTransfersWithOffset(domain, filter)
      _ <- ingestionSink.ingestInFlightTransfers(domain, tfs)
      _ <- ingestionSink.switchToIngestingUpdates(domain, off.getOffset)
    } yield off
  }

  // Kick-off the ingestion
  startIngestion()
}
