package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.ledger.client.binding.Primitive
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.CN.{Directory => directoryCodegen, Wallet => walletCodegen}
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.store.AcsStore.QueryResult
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.ShowUtil._

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Manages background automation that runs on an directory app. */
class DirectoryAutomationService(
    store: DirectoryStore,
    ledgerClient: CoinLedgerClient,
    protected val loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
) extends AutomationService {

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

  registerService(
    new AcsIngestionService(
      this.getClass.getSimpleName,
      store.acsIngestionSink,
      connection,
      loggerFactory,
      timeouts,
    )
  )

  registerTaskHandler("DirectoryInstallRequest", store.streamInstallRequests())(
    req => "install request: " + req.toString.singleQuoted,
    req => {
      val user = PartyId.tryFromPrim(req.payload.user)
      store.lookupInstall(user).flatMap {
        case QueryResult(_, Some(_)) =>
          logger.info(
            s"Rejecting install request ${req.contractId} from user $user: duplicate"
          )
          val cmd = req.contractId.exerciseDirectoryInstallRequest_Reject()
          connection.submitWithResult(Seq(provider), Seq(), cmd)

        case QueryResult(_, None) =>
          val arg = directoryCodegen.DirectoryInstallRequest_Accept(
            svc = store.svcParty.toPrim,
            entryFee = entryFee,
            collectionDuration = collectionDuration,
            acceptDuration = acceptDuration,
          )
          val acceptCmd = req.contractId.exerciseDirectoryInstallRequest_Accept(arg)
          // TODO(#790): should we really discard the result here? if yes, can we avoid parsing it?
          // TODO(#790): use command-dedup to protect against races
          connection.submitWithResult(Seq(provider), Seq(), acceptCmd).map(_ => ())
      }
    },
  )

  registerTaskHandler("DirectoryEntryRequest", store.streamEntryRequests())(
    req => "entry request: " + req.toString.singleQuoted,
    req => {
      val user = PartyId.tryFromPrim(req.payload.entry.user)
      val rejectRequest = (reason: String) => {
        logger.info(
          s"Rejecting entry request ${req.contractId} from user $user: $reason."
        )
        val arg = directoryCodegen.DirectoryEntryRequest_Reject()
        val cmd = req.contractId.exerciseDirectoryEntryRequest_Reject(arg)
        connection.submitWithResult(Seq(provider), Seq(), cmd)
      }
      store.lookupInstall(user).flatMap {
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
    },
  )

  registerTaskHandler("AcceptedAppPaymentRequests", store.streamAcceptedAppPayments())(
    payment => "accepted app payment: " + payment.toString.singleQuoted,
    payment => {
      val offerId = payment.payload.deliveryOffer
        .unsafeToTemplate[directoryCodegen.DirectoryEntryOffer]
      store.lookupEntryOfferById(offerId).flatMap {
        case QueryResult(_, None) =>
          // TODO(#790): change logger to use tracing to track the actions of the handler
          logger.error(s"Invariant violation: reference offer $offerId not known")
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
    },
  )
}
