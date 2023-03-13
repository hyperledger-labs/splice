package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CoinLedgerConnection, CoinLedgerSubscription, CoinRetries}
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{
  TransferEvent,
  TransferUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.store.TransferStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId
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
  ): Future[CoinLedgerSubscription[?]] =
    Future.successful {
      connection.subscribeAsync(
        this.getClass.getSimpleName,
        loggerFactory,
        // TODO(#2729) Stop streaming from ledger begin here.
        LedgerOffset.LedgerBegin.getInstance,
        ingestionSink.ingestionFilter,
        domain,
      ) {
        case TransactionTreeUpdate(_) => Future.unit
        case TransferUpdate(transfer) =>
          transfer.event match {
            case out: TransferEvent.Out =>
              ingestionSink.ingestTransferOut(transfer.copy(event = out))
            case in: TransferEvent.In =>
              require(in.target == domain)
              ingestionSink.ingestTransferIn(transfer.copy(event = in))
          }
      }
    }

  // Kick-off the ingestion
  startIngestion()
}
