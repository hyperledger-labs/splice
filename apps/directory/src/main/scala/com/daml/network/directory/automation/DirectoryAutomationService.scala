package com.daml.network.directory.automation

import com.daml.network.scan.admin.api.client.ScanConnection
import akka.stream.Materializer
import com.daml.ledger.client.binding.Primitive
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.CN.{Directory as directoryCodegen, Wallet as walletCodegen}
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.Contract
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import java.security.MessageDigest
import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an directory app. */
class DirectoryAutomationService(
    store: DirectoryStore,
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(retryProvider) {

  private val entryFee: Primitive.Numeric = 1.0
  private val collectionDuration = RelTime(
    10_000_000
  )
  private val acceptDuration = RelTime(
    60_000_000
  )
  private val renewalDuration = RelTime(
    // 30 days
    30 * 24 * 60 * 1_000_000
  )
  private val entryLifetime = RelTime(
    // 90 days
    90 * 24 * 60 * 1_000_000
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

  private val hashFun = MessageDigest.getInstance("SHA-256")

  // TODO(#790): generalize this so that it can be shared with other command dedup calls
  private def mkCommandId(
      methodName: String,
      parties: Seq[PartyId],
      suffix: String = "",
  ): String = {
    require(!methodName.contains('/'))
    val str = parties.map(_.toProtoPrimitive).appended(suffix).mkString("/")
    val hash = hashFun.digest(str.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"${methodName}_$hash"
  }

  registerRequestHandler("handleDirectoryInstallRequest", store.streamInstallRequests())(req => {
    implicit traceContext =>
      {
        val user = PartyId.tryFromPrim(req.payload.user)
        store.lookupInstall(user).flatMap {
          case QueryResult(_, Some(_)) =>
            logger.info(s"Rejecting duplicate install request from user party $user")
            val cmd = req.contractId.exerciseDirectoryInstallRequest_Reject()
            connection
              .submitWithResult(Seq(provider), Seq(), cmd)
              .map(_ => "rejected request for already existing installation.")

          case QueryResult(off, None) =>
            val arg = directoryCodegen.DirectoryInstallRequest_Accept(
              svc = store.svcParty.toPrim,
              entryFee = entryFee,
              collectionDuration = collectionDuration,
              acceptDuration = acceptDuration,
              renewalDuration = renewalDuration,
              entryLifetime = entryLifetime,
            )
            val commandId =
              mkCommandId("com.daml.network.directory.DirectoryInstall", Seq(provider, user))

            val acceptCmd = req.contractId.exerciseDirectoryInstallRequest_Accept(arg).command
            // TODO(#790): should we really discard the result here? if yes, can we avoid parsing it?
            connection
              .submitCommandWithDedup(
                actAs = Seq(provider),
                readAs = Seq(),
                command = Seq(acceptCmd),
                commandId = commandId,
                deduplicationOffset = off,
              )
              .map(_ => "accepted install request.")
        }
      }
  })

  registerRequestHandler("handleDirectoryEntryRequest", store.streamEntryRequests())(req => {
    implicit traceContext =>
      {
        val user = PartyId.tryFromPrim(req.payload.user)
        val rejectRequest = (reason: String) => {
          val arg = directoryCodegen.DirectoryEntryRequest_Reject()
          val cmd = req.contractId.exerciseDirectoryEntryRequest_Reject(arg)
          connection
            .submitWithResult(Seq(provider), Seq(), cmd)
            .map(_ => s"rejected request: $reason")
        }
        store.lookupInstall(user).flatMap {
          case QueryResult(_, None) =>
            logger.warn(s"Install contract not found for user party $user")
            rejectRequest("install contract not found.")

          case QueryResult(_, Some(install)) =>
            // TODO(M3-90): validate entry name
            store.lookupEntryByName(req.payload.name).flatMap {
              case QueryResult(_, Some(entry)) =>
                rejectRequest(s"already exists and owned by ${entry.payload.user}.")

              case QueryResult(_, None) =>
                val arg = directoryCodegen.DirectoryInstall_RequestEntryPayment(
                  requestCid = req.contractId
                )
                val cmd = install.contractId.exerciseDirectoryInstall_RequestEntryPayment(arg)
                connection
                  .submitWithResult(Seq(provider), Seq(), cmd)
                  .map(_ => "requested payment for entry request.")
            }
        }
      }
  })

  registerRequestHandler("handleAcceptedAppPaymentRequests", store.streamAcceptedAppPayments())(
    payment => { implicit traceContext =>
      {
        val offerId = payment.payload.deliveryOffer
          .unsafeToTemplate[directoryCodegen.DirectoryEntryOffer]
        def rejectPayment(reason: String) = {
          logger.warn(s"rejecting accepted app payment: $reason")
          val arg = walletCodegen.AcceptedAppPayment_Reject()
          val cmd = payment.contractId.exerciseAcceptedAppPayment_Reject(arg)
          connection
            .submitWithResult(Seq(provider), Seq(), cmd)
            .map(_ => s"rejected accepted app payment: $reason")
        }
        def collectPayment(
            install: Contract[directoryCodegen.DirectoryInstall],
            entryName: String,
            offset: String,
        ) = {
          val commandId = mkCommandId(
            "com.daml.network.directory.DirectoryEntry",
            Seq(provider),
            entryName,
          )
          for {
            openRound <- scanConnection.getLatestOpenMiningRound()
            cmd =
              install.contractId
                .exerciseDirectoryInstall_CollectEntryPayment(
                  payment.contractId,
                  openRound.contractId,
                )
                .command
            _ <- connection
              .submitCommandWithDedup(
                actAs = Seq(provider),
                readAs = Seq.empty,
                command = Seq(cmd),
                commandId = commandId,
                deduplicationOffset = offset,
              )
          } yield "created directory entry."
        }
        for {
          offer <- store
            .lookupEntryOfferById(offerId)
            .map(
              _.value.getOrElse(
                throw new IllegalStateException(
                  s"Invariant violation: reference offer $offerId not known"
                )
              )
            )
          // shared values
          user = PartyId.tryFromPrim(offer.payload.entryRequest.user)
          // TODO(M3-90): understand what kind of assertions are worth checking here for defensive programming
          entryName = offer.payload.entryRequest.name
          // retrieve install for the user
          result <- store.lookupInstall(user).flatMap {
            case QueryResult(_, None) => rejectPayment("directory install contract not found.")

            case QueryResult(_, Some(install)) =>
              // check whether the entry already exists
              store.lookupEntryByName(entryName).flatMap {
                case QueryResult(_, Some(entry)) =>
                  rejectPayment(s"entry already exists and owned by ${entry.payload.user}.")
                case QueryResult(off, None) =>
                  // collect the payment and create the entry
                  collectPayment(install, entryName, off)
              }
          }
        } yield result
      }
    }
  )
}
