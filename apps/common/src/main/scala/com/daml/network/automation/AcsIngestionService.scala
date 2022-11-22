package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.store.AcsStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{NoTracing, Spanning}
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import com.digitalasset.canton.util.{FutureUtil, retry}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

class AcsIngestionService(
    name: String,
    ingestionSink: AcsStore.IngestionSink,
    connection: CoinLedgerConnection,
    override protected val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends FlagCloseableAsync
    with NamedLogging
    with Spanning
    with NoTracing {

  private val txFilter = ingestionSink.transactionFilter
  private val serviceDescriptor = s"AcsIngestionService($name, ${txFilter.getParties.toString})"

  // TODO(#790): double-check and cleanup the usage of retry here
  implicit val success: retry.Success[Any] = retry.Success.always
  val policy = (msg: String) =>
    retry.Backoff(
      logger,
      this,
      retry.Forever,
      1.seconds,
      10.seconds,
      msg,
    )

  // TODO(#790): stream contracts instead of ingesting them as a single Seq
  private val subscriptionF = {
    withNewTrace(serviceDescriptor)(implicit traceContext =>
      _ => {
        logger.debug(s"Starting $serviceDescriptor")
        FutureUtil.logOnFailure(
          for {
            // TODO(#790): stop retrying if we're already closing
            (evs, off) <- policy(s"Get active contracts for $txFilter")(
              connection.activeContractsWithOffset(txFilter),
              AllExnRetryable,
            )
            () <- ingestionSink.ingestActiveContracts(evs)
            () <- ingestionSink.switchToIngestingTransactions(
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
    // TODO(#790): handle this case seen in the CoinLedgerConnection code
    // case Failure(ex: StatusRuntimeException) =>
    // don't fail to close if there was a grpc status runtime exception
    // this can happen (i.e. server not available etc.)
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    AsyncCloseable(
      serviceDescriptor,
      subscriptionF,
      timeouts.shutdownNetwork.duration,
    )
  )
}
