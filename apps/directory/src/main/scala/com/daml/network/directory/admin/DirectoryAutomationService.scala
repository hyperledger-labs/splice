package com.daml.network.directory.admin

import akka.stream.Materializer
import akka.stream.scaladsl._
import com.daml.ledger.client.binding.Primitive
import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.codegen.CN.{Directory => directoryCodegen, Wallet => walletCodegen}
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.directory.store.DirectoryAppStore
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.Contract
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  Lifecycle,
  SyncCloseable,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.ShowUtil._
import com.digitalasset.canton.util.{FutureUtil, retry}
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

/** Manages background automation that runs on an directory app.
  */
class DirectoryAutomationService(
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
  private val provider = store.providerParty

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
  val txFilter = store.acsIngestionSink.transactionFilter
  val subscriptionF =
    FutureUtil.logOnFailure(
      for {
        (evs, off) <- policy(s"Get active contracts for $txFilter")(
          connection.activeContractsWithOffset(txFilter),
          AllExnRetryable,
        )
        () <- store.acsIngestionSink.ingestActiveContracts(evs)
        () <- store.acsIngestionSink.switchToIngestingTransactions(
          off.value.absolute.getOrElse(sys.error("expected absolute offset"))
        )
      } yield connection.subscribeAsync("DirectoryStoreTransactionIngestion", off, txFilter)(
        store.acsIngestionSink.ingestTransaction(_)
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
      lazy val prettiedTask = "install request: " + req.toString.singleQuoted
      logger.debug(s"Attempting to process $prettiedTask...")
      val submissionF = for {
        // TODO(#790): fetch this party outside the inner loop
        svc <- scanConnection.getSvcPartyId()
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

  private[this] val entryRequestHandler = connection.makeSubscription(
    store.streamEntryRequests(),
    // TODO(#790): make parallelism configurable, and handle retries properly
    Flow[Contract[directoryCodegen.DirectoryEntryRequest]].mapAsync(1)(req => {
      lazy val prettiedTask = "entry request: " + req.toString.singleQuoted
      logger.debug(s"Attempting to process $prettiedTask...")
      val user = PartyId.tryFromPrim(req.payload.entry.user)
      val rejectRequest = (reason: String) => {
        logger.info(
          s"Rejecting entry request ${req.contractId} from user $user: $reason."
        )
        val arg = directoryCodegen.DirectoryEntryRequest_Reject()
        val cmd = req.contractId.exerciseDirectoryEntryRequest_Reject(arg)
        connection.submitWithResult(Seq(provider), Seq(), cmd)
      }
      val submissionF = store.lookupInstall(user).flatMap {
        case QueryResult(_, None) => rejectRequest("install contract not found.")

        case QueryResult(_, Some(install)) =>
          // TODO(M3-90): validate entry name
          store.lookupEntryByName(req.payload.entry.name).flatMap {
            case QueryResult(_, Some(entry)) =>
              rejectRequest(s"already exists and owned by ${entry.payload.user}.")

            case QueryResult(_, None) =>
              logger.debug(
                s"Requesting payment for entry request ${req.contractId} from user $user."
              )
              val arg = directoryCodegen.DirectoryInstall_RequestEntryPayment(
                requestCid = req.contractId
              )
              val cmd = install.contractId.exerciseDirectoryInstall_RequestEntryPayment(arg)
              connection.submitWithResult(Seq(provider), Seq(), cmd).map(_ => ())
          }
      }
      submissionF.onComplete {
        case Success(_) =>
          logger.debug(s"Successfully processed $prettiedTask.")
        case Failure(ex) =>
          logger.warn(s"Processing $prettiedTask failed! Reason:", ex)
      }
      submissionF
    }),
    "handle directory entry requests ",
  )

  private[this] val acceptedAppPaymentHandler = connection.makeSubscription(
    store.streamAcceptedAppPayments(),
    // TODO(#790): make parallelism configurable, and handle retries properly
    Flow[Contract[walletCodegen.AcceptedAppPayment]].mapAsync(1)(payment => {
      lazy val prettiedTask = "accepted app payment: " + payment.toString.singleQuoted
      logger.debug(s"Attempting to process $prettiedTask...")
      val offerId = payment.payload.deliveryOffer
        .unsafeToTemplate[directoryCodegen.DirectoryEntryOffer]
      val submissionF = store.lookupEntryOfferById(offerId).flatMap {
        case QueryResult(_, None) =>
          logger
            .error(s"Invariant violation: reference offer $offerId not known in $prettiedTask")
          Future.unit

        case QueryResult(_, Some(offer)) =>
          // shared values
          val user = PartyId.tryFromPrim(offer.payload.entry.user)
          // TODO(M3-90): understand what kind of assertions are worth checking here for defensive programming
          val entryName = offer.payload.entry.name
          def rejectPayment(reason: String) = {
            logger.info(
              s"Rejecting accepted app payment from $user for entry '$entryName': $reason."
            )
            val arg = walletCodegen.AcceptedAppPayment_Reject()
            val cmd = payment.contractId.exerciseAcceptedAppPayment_Reject(arg)
            connection.submitWithResult(Seq(provider), Seq(), cmd).map(_ => ())
          }

          // retrieve install for the user
          store.lookupInstall(user).flatMap {
            case QueryResult(_, None) => rejectPayment("directory install contract not found.")

            case QueryResult(_, Some(install)) =>
              // check whether the entry already exists
              store.lookupEntryByName(entryName).flatMap {
                case QueryResult(_, Some(entry)) =>
                  rejectPayment(s"entry already exists and owned by ${entry.payload.user}.")

                case QueryResult(off, None) =>
                  // collect the payment and create the entry
                  // TODO(#321): use command deduplication to protect against concurrent submissions
                  logger.debug(
                    s"Creating directory entry '$entryName' for user $user."
                  )
                  val arg =
                    directoryCodegen.DirectoryInstall_CollectEntryPayment(payment.contractId)
                  val cmd = install.contractId.exerciseDirectoryInstall_CollectEntryPayment(arg)
                  connection.submitWithResult(Seq(provider), Seq(), cmd).map(_ => ())
              }
          }
      }
      submissionF.onComplete {
        case Success(_) =>
          logger.debug(s"Successfully processed $prettiedTask.")
        case Failure(ex) =>
          logger.warn(s"Processing $prettiedTask failed! Reason:", ex)
      }
      submissionF
    }),
    "handle directory entry requests ",
  )

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      SyncCloseable(
        "Directory automation services",
        Lifecycle.close(installRequestHandler, entryRequestHandler, acceptedAppPaymentHandler)(
          logger
        ),
      ),
      AsyncCloseable(
        "DirectoryStoreIngestionSubscription",
        subscriptionF,
        processingTimeouts.shutdownNetwork.duration,
      ),
    )
}
