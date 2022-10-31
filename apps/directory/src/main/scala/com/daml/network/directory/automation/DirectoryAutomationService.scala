package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.ledger.client.binding.Primitive
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.CN.{Directory as directoryCodegen}
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
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
    // TODO(i1395) Consider reverting this to 30 days once our tests can control time
    // 90 days
    90 * 24 * 60 * 60 * 1_000_000L
  )
  private val entryLifetime = RelTime(
    // 90 days
    90 * 24 * 60 * 60 * 1_000_000L
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

  registerRequestHandler(
    "handleAcceptedAppMultiPaymentRequests",
    store.streamAcceptedAppMultiPayments(),
  )(payment => { implicit traceContext =>
    {
      val offerId = payment.payload.deliveryOffer
        .unsafeToTemplate[directoryCodegen.DirectoryEntryOffer]
      def rejectPayment(reason: String) = {
        logger.warn(s"rejecting accepted app payment: $reason")
        val cmd = payment.contractId.exerciseAcceptedAppMultiPayment_Reject()
        connection
          .submitWithResult(Seq(provider), Seq(), cmd)
          .map(_ => s"rejected accepted app payment: $reason")
      }
      def collectPayment(
          offer: Contract[directoryCodegen.DirectoryEntryOffer],
          entryName: String,
          offset: String,
      ) = {
        val commandId = mkCommandId(
          "com.daml.network.directory.DirectoryEntry",
          Seq(provider),
          entryName,
        )
        for {
          transferContext <- scanConnection.getAppTransferContext()
          cmd =
            offer.contractId
              .exerciseDirectoryEntryOffer_CollectPayment(
                payment.contractId,
                transferContext,
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
        // TODO(M3-90): understand what kind of assertions are worth checking here for defensive programming
        entryName = offer.payload.entryRequest.name
        // check whether the entry already exists
        result <- store.lookupEntryByName(entryName).flatMap {
          case QueryResult(_, Some(entry)) =>
            rejectPayment(s"entry already exists and owned by ${entry.payload.user}.")
          case QueryResult(off, None) =>
            // collect the payment and create the entry
            collectPayment(offer, entryName, off)
        }
      } yield result
    }
  })

  registerRequestHandler(
    "handleSubscriptionInitialPayments",
    store.streamSubscriptionInitialPayments(),
  )(payment => { implicit traceContext =>
    {
      val contextId = payment.payload.subscriptionData.context
        .unsafeToTemplate[directoryCodegen.DirectoryEntryContext]
      def rejectPayment(reason: String) = {
        logger.warn(s"rejecting initial subscription payment: $reason")
        val cmd = payment.contractId.exerciseSubscriptionInitialPayment_Reject()
        connection
          .submitWithResult(Seq(provider), Seq(), cmd)
          .map(_ => s"rejected initial subscription payment: $reason")
      }
      def collectPayment(
          context: Contract[directoryCodegen.DirectoryEntryContext],
          entryName: String,
          offset: String,
      ) = {
        val user = PartyId.tryFromPrim(context.payload.user)
        store.lookupInstall(user).flatMap {
          case QueryResult(_, None) =>
            logger.warn(s"Install contract not found for user party $user")
            rejectPayment("install contract not found.")

          case QueryResult(_, Some(install)) =>
            for {
              transferContext <- scanConnection.getAppTransferContext()
              cmd =
                install.contractId
                  .exerciseDirectoryInstall_CollectInitialEntryPaymentWithSubscription(
                    payment.contractId,
                    transferContext,
                  )
                  .command
              commandId = mkCommandId(
                "com.daml.network.directory.DirectoryEntry",
                Seq(provider),
                entryName,
              )
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
      }
      for {
        context <- store
          .lookupEntryContextById(contextId)
          .map(
            _.value.getOrElse(
              throw new IllegalStateException(
                s"Invariant violation: subscription context $contextId not known"
              )
            )
          )
        // TODO(M3-90): understand what kind of assertions are worth checking here for defensive programming
        entryName = context.payload.name
        // check whether the entry already exists
        result <- store.lookupEntryByName(entryName).flatMap {
          case QueryResult(_, Some(entry)) =>
            rejectPayment(s"entry already exists and owned by ${entry.payload.user}.")
          case QueryResult(off, None) =>
            // collect the payment and create the entry
            collectPayment(context, entryName, off)
        }
      } yield result
    }
  })

  registerRequestHandler(
    "handleSubscriptionPayments",
    store.streamSubscriptionPayments(),
  )(payment => { implicit traceContext =>
    {
      val contextId = payment.payload.subscriptionData.context
        .unsafeToTemplate[directoryCodegen.DirectoryEntryContext]
      def rejectPayment(reason: String) = {
        logger.warn(s"rejecting subscription payment: $reason")
        val cmd = payment.contractId.exerciseSubscriptionPayment_Reject()
        connection
          .submitWithResult(Seq(provider), Seq(), cmd)
          .map(_ => s"rejected subscription payment: $reason")
      }
      def collectPayment(
          context: Contract[directoryCodegen.DirectoryEntryContext],
          entry: Contract[directoryCodegen.DirectoryEntry],
          offset: String,
      ) = {
        val user = PartyId.tryFromPrim(context.payload.user)
        store.lookupInstall(user).flatMap {
          case QueryResult(_, None) =>
            logger.warn(s"Install contract not found for user party $user")
            rejectPayment("install contract not found.")

          case QueryResult(_, Some(install)) =>
            for {
              transferContext <- scanConnection.getAppTransferContext()
              cmd =
                install.contractId
                  .exerciseDirectoryInstall_CollectEntryRenewalPaymentWithSubscription(
                    payment.contractId,
                    entry.contractId,
                    transferContext,
                  )
                  .command
              commandId = mkCommandId(
                "com.daml.network.directory.DirectoryEntry",
                Seq(provider),
                entry.payload.name,
              )
              _ <- connection
                .submitCommandWithDedup(
                  actAs = Seq(provider),
                  readAs = Seq.empty,
                  command = Seq(cmd),
                  commandId = commandId,
                  deduplicationOffset = offset,
                )
            } yield "renewed directory entry."
        }
      }
      for {
        context <- store
          .lookupEntryContextById(contextId)
          .map(
            _.value.getOrElse(
              throw new IllegalStateException(
                s"Invariant violation: subscription context $contextId not known"
              )
            )
          )
        // TODO(M3-90): understand what kind of assertions are worth checking here for defensive programming
        entryName = context.payload.name
        // check whether the entry exists
        result <- store.lookupEntryByName(entryName).flatMap {
          case QueryResult(off, Some(entry)) =>
            // collect the payment and renew the entry
            collectPayment(context, entry, off)
          case QueryResult(_, None) =>
            rejectPayment("entry doesn't exist.")
        }
      } yield result
    }
  })
}
