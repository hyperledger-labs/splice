package com.daml.network.directory.admin

import akka.stream.Materializer
import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.directory.store.DirectoryAppStore
import com.daml.network.environment.CoinLedgerClient
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.util.FutureUtil
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import com.digitalasset.canton.util.retry.{Backoff, Forever, Success}
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

/** Manages background automation that runs on an directory app.
  */
class DirectoryAutomationService(
    damlUser: String,
    store: DirectoryAppStore,
    ledgerClient: CoinLedgerClient,
    loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
    @nowarn("cat=unused")
    mat: Materializer,
) extends LedgerAutomationServiceOrchestrator(loggerFactory)(
      ec,
      tracer,
    ) {
  override protected def timeouts: ProcessingTimeout = processingTimeouts

  private val connection = ledgerClient.connection("DirectoryAutomationService")

  // TODO(#790): double-check and cleanup the usage of retry here
  implicit val success: Success[Any] = Success.always
  val policy = (msg: String) =>
    Backoff(
      logger,
      this,
      Forever,
      1.seconds,
      10.seconds,
      msg,
    )

  // Spawn off futures required to initialize the store
  // TODO(M3-90): stream contracts instead of ingesting them as a single Seq
  logger.debug("Attempting to ingest the ACS and initialize the transaction subscription.")
  val subscriptionF =
    FutureUtil.logOnFailure(
      for {
        filter <- store.transactionFilter
        (evs, off) <- policy(s"Get active contracts for $filter")(
          connection.activeContractsWithOffset(filter),
          AllExnRetryable,
        )
        () <- store.ingestActiveContracts(evs)
        () <- store.switchToIngestingTransactions(
          off.value.absolute.getOrElse(sys.error("expected absolute offset"))
        )
      } yield connection.subscribeAsync("DirectoryStoreTransactionIngestion", off, filter)(
        store.ingestTransaction(_)
      ),
      "Failed to ingest the ACS and initialize transaction subscription.",
    )

  subscriptionF.foreach(_ => logger.debug("Ingested ACS and initialized transaction subscription."))

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      AsyncCloseable(
        "DirectoryStoreIngestionSubscription",
        subscriptionF,
        processingTimeouts.shutdownNetwork.duration,
      )
    )
}
