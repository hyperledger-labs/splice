package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cn.{directory => directoryCodegen}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.{JavaContract => Contract}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import java.security.MessageDigest
import scala.concurrent.ExecutionContextExecutor
import scala.jdk.CollectionConverters.*

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

  private val entryFee: BigDecimal = 1.0
  private val collectionDuration = new RelTime(
    10_000_000
  )
  private val acceptDuration = new RelTime(
    60_000_000
  )
  private val renewalDuration = new RelTime(
    // 90 days; keeping this generous makes testing easier
    90 * 24 * 60 * 60 * 1_000_000L
  )
  private val entryLifetime = new RelTime(
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

  // TODO(#790): generalize this so that it can be shared with other command dedup calls
  private def mkCommandId(
      methodName: String,
      parties: Seq[PartyId],
      suffix: String = "",
  ): String = {
    require(!methodName.contains('/'))
    val str = parties.map(_.toProtoPrimitive).appended(suffix).mkString("/")
    // Digest is not thread safe, create a new one each time.
    val hashFun = MessageDigest.getInstance("SHA-256")
    val hash = hashFun.digest(str.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"${methodName}_$hash"
  }

  registerRequestHandler("handleDirectoryInstallRequest", store.streamInstallRequests())(req =>
    implicit traceContext => {
      val user = PartyId.tryFromProtoPrimitive(req.payload.user)
      store.lookupInstall(user).flatMap {
        case QueryResult(_, Some(_)) =>
          logger.info(s"Rejecting duplicate install request from user party $user")
          val cmd = req.contractId
            .exerciseDirectoryInstallRequest_Reject()
          connection
            .submitCommands(Seq(provider), Seq(), cmd.commands.asScala.toSeq)
            .map(_ => "rejected request for already existing installation.")

        case QueryResult(off, None) =>
          val arg = new directoryCodegen.DirectoryInstallRequest_Accept(
            store.svcParty.toProtoPrimitive,
            entryFee.bigDecimal,
            collectionDuration,
            acceptDuration,
            renewalDuration,
            entryLifetime,
          )
          val commandId =
            mkCommandId("com.daml.network.directory.DirectoryInstall", Seq(provider, user))

          val acceptCmd = req.contractId
            .exerciseDirectoryInstallRequest_Accept(arg)
          // TODO(#790): should we really discard the result here? if yes, can we avoid parsing it?
          connection
            .submitCommandsWithDedup(
              actAs = Seq(provider),
              readAs = Seq(),
              commands = acceptCmd.commands.asScala.toSeq,
              commandId = commandId,
              deduplicationOffset = off,
            )
            .map(_ => "accepted install request.")
      }
    }
  )

  registerRequestHandler("handleDirectoryEntryRequest", store.streamEntryRequests())(req =>
    implicit traceContexf => {
      val user = PartyId.tryFromProtoPrimitive(req.payload.user)
      val rejectRequest = (reason: String) => {
        val arg = new directoryCodegen.DirectoryEntryRequest_Reject()
        val cmd = req.contractId
          .exerciseDirectoryEntryRequest_Reject(arg)
        connection
          .submitCommands(Seq(provider), Seq(), cmd.commands.asScala.toSeq)
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
              val arg = new directoryCodegen.DirectoryInstall_RequestEntryPayment(
                req.contractId
              )
              val cmd = install.contractId
                .exerciseDirectoryInstall_RequestEntryPayment(arg)
              connection
                .submitCommands(Seq(provider), Seq(), cmd.commands.asScala.toSeq)
                .map(_ => "requested payment for entry request.")
          }
      }
    }
  )

  registerRequestHandler(
    "handleAcceptedAppPaymentRequests",
    store.streamAcceptedAppPayments(),
  )(payment => { implicit traceContext =>
    {
      val offerId = directoryCodegen.DirectoryEntryOffer.ContractId.unsafeFromInterface(
        payment.payload.deliveryOffer
      )
      def rejectPayment(reason: String) = {
        logger.warn(s"rejecting accepted app payment: $reason")
        val cmd = payment.contractId.exerciseAcceptedAppPayment_Reject()
        connection
          .submitWithResult(Seq(provider), Seq(), cmd)
          .map(_ => s"rejected accepted app payment: $reason")
      }
      def collectPayment(
          offer: Contract[
            directoryCodegen.DirectoryEntryOffer.ContractId,
            directoryCodegen.DirectoryEntryOffer,
          ],
          entryName: String,
          offset: String,
      ) = {
        val commandId = mkCommandId(
          "com.daml.network.directory.DirectoryEntry",
          Seq(provider),
          entryName,
        )
        for {
          transferContext <- scanConnection.getJavaAppTransferContext()
          cmd =
            offer.contractId
              .exerciseDirectoryEntryOffer_CollectPayment(
                payment.contractId,
                transferContext,
              )
              .commands
          _ <- connection
            .submitCommandsWithDedup(
              actAs = Seq(provider),
              readAs = Seq.empty,
              commands = cmd.asScala.toSeq,
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
      val contextId = directoryCodegen.DirectoryEntryContext.ContractId.unsafeFromInterface(
        payment.payload.subscriptionData.context
      )
      def rejectPayment(reason: String) = {
        logger.warn(s"rejecting initial subscription payment: $reason")
        val cmd = payment.contractId.exerciseSubscriptionInitialPayment_Reject()
        connection
          .submitWithResult(Seq(provider), Seq(), cmd)
          .map(_ => s"rejected initial subscription payment: $reason")
      }
      def collectPayment(
          context: Contract[
            directoryCodegen.DirectoryEntryContext.ContractId,
            directoryCodegen.DirectoryEntryContext,
          ],
          entryName: String,
          offset: String,
      ) = {
        for {
          transferContext <- scanConnection.getJavaAppTransferContext()
          cmd =
            contextId
              .exerciseDirectoryEntryContext_CollectInitialEntryPaymentWithSubscription(
                payment.contractId,
                transferContext,
              )
              .commands
          commandId = mkCommandId(
            "com.daml.network.directory.DirectoryEntry",
            Seq(provider),
            entryName,
          )
          _ <- connection
            .submitCommandsWithDedup(
              actAs = Seq(provider),
              readAs = Seq.empty,
              commands = cmd.asScala.toSeq,
              commandId = commandId,
              deduplicationOffset = offset,
            )
        } yield "created directory entry."
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
      val contextId = directoryCodegen.DirectoryEntryContext.ContractId.unsafeFromInterface(
        payment.payload.subscriptionData.context
      )
      def rejectPayment(reason: String) = {
        logger.warn(s"rejecting subscription payment: $reason")
        val cmd = payment.contractId.exerciseSubscriptionPayment_Reject()
        connection
          .submitWithResult(Seq(provider), Seq(), cmd)
          .map(_ => s"rejected subscription payment: $reason")
      }
      def collectPayment(
          context: Contract[
            directoryCodegen.DirectoryEntryContext.ContractId,
            directoryCodegen.DirectoryEntryContext,
          ],
          entry: Contract[
            directoryCodegen.DirectoryEntry.ContractId,
            directoryCodegen.DirectoryEntry,
          ],
          offset: String,
      ) = {
        for {
          transferContext <- scanConnection.getJavaAppTransferContext()
          cmd =
            contextId
              .exerciseDirectoryEntryContext_CollectEntryRenewalPaymentWithSubscription(
                payment.contractId,
                entry.contractId,
                transferContext,
              )
              .commands
          commandId = mkCommandId(
            "com.daml.network.directory.DirectoryEntry",
            Seq(provider),
            entry.payload.name,
          )
          _ <- connection
            .submitCommandsWithDedup(
              actAs = Seq(provider),
              readAs = Seq.empty,
              commands = cmd.asScala.toSeq,
              commandId = commandId,
              deduplicationOffset = offset,
            )
        } yield "renewed directory entry."
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
