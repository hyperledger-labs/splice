package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.store.AcsStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.FutureUtil
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

class AcsIngestionService(
    name: String,
    ingestionSink: AcsStore.IngestionSink,
    connection: CoinLedgerConnection,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends FlagCloseableAsync
    with NamedLogging
    with Spanning {

  private val txFilter = ingestionSink.transactionFilter
  private val serviceDescriptor = s"AcsIngestionService($name, ${txFilter.getParties.toString})"

  // TODO(#790): stream contracts instead of ingesting them as a single Seq
  private val subscriptionF = {
    withNewTrace(serviceDescriptor)(implicit traceContext =>
      _ => {
        logger.debug(s"Starting $serviceDescriptor")
        FutureUtil.logOnFailure(
          for {
            (evs, off) <- retryProvider.retryForAutomationWithUncleanShutdown(
              s"Get active contracts for $txFilter",
              connection.activeContractsWithOffset(txFilter),
              this,
            )
            case () <- ingestionSink.ingestActiveContracts(evs)
            case () <- ingestionSink.switchToIngestingTransactions(
              off match {
                case absolute: LedgerOffset.Absolute => absolute.getOffset
                case _ => sys.error("expected absolute offset")
              }
            )
          } yield connection.subscribeAsync("AcsIngestionService:" + name, off, txFilter)(
            ingestionSink.ingestTransaction(_)
          ),
          "Failed to ingest the ACS and initialize transaction subscription.",
        )
      }
    )
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    implicit def traceContext: TraceContext = TraceContext.empty
    Seq(
      AsyncCloseable(
        serviceDescriptor,
        subscriptionF,
        timeouts.shutdownNetwork.duration,
      )
    )
  }
}
