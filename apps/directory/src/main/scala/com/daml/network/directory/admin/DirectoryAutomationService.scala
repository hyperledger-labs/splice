package com.daml.network.directory.admin

import akka.stream.scaladsl._
import akka.stream.Materializer
import com.daml.ledger.client.binding.Primitive
import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.directory.store.DirectoryAppStore
import com.daml.network.environment.CoinLedgerClient
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  Lifecycle,
  SyncCloseable,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.util.FutureUtil
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import com.digitalasset.canton.util.retry
import io.opentelemetry.api.trace.Tracer
import com.daml.network.codegen.CN.{Directory => directoryCodegen}
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.Contract
import com.digitalasset.canton.util.ShowUtil._

import scala.annotation.nowarn
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/** Manages background automation that runs on an directory app.
  */
class DirectoryAutomationService(
    damlUser: String,
    store: DirectoryAppStore,
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
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

  private val entryFee: Primitive.Numeric = 1.0
  private val collectionDuration = RelTime(
    10_000_000
  )
  private val acceptDuration = RelTime(
    60_000_000
  )

  override protected def timeouts: ProcessingTimeout = processingTimeouts

  private val connection = ledgerClient.connection("DirectoryAutomationService")

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

  // Create install request handler
  // TODO(#790): organize the code more cleanly around this
  private[this] val installRequestHandler = connection.makeSubscription(
    store.streamInstallRequests(),
    // TODO(#790): make parallelism configurable, and handle retries properly
    Flow[Contract[directoryCodegen.DirectoryInstallRequest]].mapAsync(1)(req => {
      lazy val prettiedTask = req.toString.singleQuoted
      logger.debug(s"Attempting to process $prettiedTask...")
      val submissionF = for {
        // TODO(#790): fetch these parties outside the inner loop
        svc <- scanConnection.getSvcPartyId()
        provider <- store.getProviderParty()
        arg = directoryCodegen.DirectoryInstallRequest_Accept(
          svc = svc.toPrim,
          entryFee = entryFee,
          collectionDuration = collectionDuration,
          acceptDuration = acceptDuration,
        )
        acceptCmd = req.contractId.exerciseDirectoryInstallRequest_Accept(arg)
        // TODO(#790): should we really discard the result here?
        _ <- connection.submitWithResult(Seq(provider), Seq(), acceptCmd)
      } yield ()
      submissionF.onComplete {
        case Success(_) =>
          logger.debug(s"Successfully processed $prettiedTask.")
        case Failure(ex) =>
          logger.warn(s"Processing $prettiedTask failed! Reason:", ex)
      }
      submissionF
    }),
    "auto-accept DirectoryInstallRequests",
  )

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      SyncCloseable(
        "Directory automation services",
        Lifecycle.close(installRequestHandler)(logger),
      ),
      AsyncCloseable(
        "DirectoryStoreIngestionSubscription",
        subscriptionF,
        processingTimeouts.shutdownNetwork.duration,
      ),
    )
}
