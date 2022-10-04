package com.daml.network.automation

import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.store.AcsStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.NoTracing
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import com.digitalasset.canton.util.{FutureUtil, retry}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class AcsIngestionService(
    name: String,
    ingestionSink: AcsStore.IngestionSink,
    connection: CoinLedgerConnection,
    override protected val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext
) extends FlagCloseableAsync
    with NoTracing
    with NamedLogging {

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
  logger.debug("Attempting to ingest the ACS and initialize the transaction subscription.")

  private val txFilter = ingestionSink.transactionFilter

  // TODO(#790): review logs produced and ensure they are specific to the streams being ingested
  private val subscriptionF = {
    FutureUtil.logOnFailure(
      for {
        // TODO(#790): stop retrying if we're already closing
        (evs, off) <- policy(s"Get active contracts for $txFilter")(
          connection.activeContractsWithOffset(txFilter),
          AllExnRetryable,
        )
        () <- ingestionSink.ingestActiveContracts(evs)
        () <- ingestionSink.switchToIngestingTransactions(
          off.value.absolute.getOrElse(sys.error("expected absolute offset"))
        )
      } yield connection.subscribeAsync("AcsIngestionService:" + name, off, txFilter)(
        ingestionSink.ingestTransaction(_)
      ),
      "Failed to ingest the ACS and initialize transaction subscription.",
    )
    // TODO(#790): handle this case seen in the CoinLedgerConnection code
    // case Failure(ex: StatusRuntimeException) =>
    // don't fail to close if there was a grpc status runtime exception
    // this can happen (i.e. server not available etc.)
  }

  subscriptionF.foreach(_ => logger.debug("Ingested ACS and initialized transaction subscription."))

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    AsyncCloseable(
      "DirectoryStoreIngestionSubscription",
      subscriptionF,
      timeouts.shutdownNetwork.duration,
    )
  )
}
