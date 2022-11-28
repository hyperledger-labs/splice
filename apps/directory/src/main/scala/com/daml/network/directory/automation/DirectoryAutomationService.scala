package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.config.AutomationConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

/** Manages background automation that runs on an directory app. */
class DirectoryAutomationService(
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
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
) extends AutomationService(automationConfig, clockConfig, retryProvider) {

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
  private val entryExpirationCheckInterval = 1.second

  override protected def timeouts: ProcessingTimeout = processingTimeouts

  private val connection = registerResource(ledgerClient.connection("DirectoryAutomationService"))
  private val provider = store.providerParty

  registerService(
    new AcsIngestionService(
      this.getClass.getSimpleName,
      store.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  private def createDirectoryEntryCommandId(
      provider: PartyId,
      entryName: String,
  ): CoinLedgerConnection.CommandId =
    CoinLedgerConnection.CommandId(
      "com.daml.network.directory.createDirectoryEntry",
      Seq(provider),
      entryName,
    )

  registerRequestHandler("handleDirectoryInstallRequest", store.streamInstallRequests())(req =>
    implicit traceContext => {
      val user = PartyId.tryFromProtoPrimitive(req.payload.user)
      store.lookupInstall(user).flatMap {
        case QueryResult(_, Some(_)) =>
          logger.info(s"Rejecting duplicate install request from user party $user")
          val cmd = req.contractId
            .exerciseDirectoryInstallRequest_Reject()
          connection
            .submitCommandsNoDedup(Seq(provider), Seq(), cmd.commands.asScala.toSeq)
            .map(_ => Some("rejected request for already existing installation."))

        case QueryResult(off, None) =>
          val arg = new directoryCodegen.DirectoryInstallRequest_Accept(
            store.svcParty.toProtoPrimitive,
            entryFee.bigDecimal,
            collectionDuration,
            acceptDuration,
            renewalDuration,
            entryLifetime,
          )
          val acceptCmd = req.contractId
            .exerciseDirectoryInstallRequest_Accept(arg)
          connection
            .submitCommands(
              actAs = Seq(provider),
              readAs = Seq(),
              commands = acceptCmd.commands.asScala.toSeq,
              commandId = CoinLedgerConnection.CommandId(
                "com.daml.network.directory.createDirectoryInstall",
                Seq(provider, user),
              ),
              deduplicationOffset = off,
            )
            .map(_ => Some("accepted install request."))
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
          .submitCommandsNoDedup(Seq(provider), Seq(), cmd.commands.asScala.toSeq)
          .map(_ => Some(s"rejected request: $reason"))
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
                .submitCommandsNoDedup(Seq(provider), Seq(), cmd.commands.asScala.toSeq)
                .map(_ => Some("requested payment for entry request."))
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
      def rejectPayment(reason: String, transferContext: v1.coin.AppTransferContext) = {
        logger.warn(s"rejecting accepted app payment: $reason")
        val cmd = payment.contractId
          .exerciseAcceptedAppPayment_Reject(transferContext)
          .commands
          .asScala
          .toSeq
        connection
          .submitCommandsNoDedup(Seq(provider), Seq(), cmd)
          .map(_ => Some(s"rejected accepted app payment: $reason"))
      }
      def collectPayment(
          offer: Contract[
            directoryCodegen.DirectoryEntryOffer.ContractId,
            directoryCodegen.DirectoryEntryOffer,
          ],
          entryName: String,
          offset: String,
          transferContext: v1.coin.AppTransferContext,
      ): Future[Option[String]] = {
        val cmd =
          offer.contractId
            .exerciseDirectoryEntryOffer_CollectPayment(
              payment.contractId,
              transferContext,
            )
            .commands
        for {
          _ <- connection
            .submitCommands(
              actAs = Seq(provider),
              readAs = Seq.empty,
              commands = cmd.asScala.toSeq,
              commandId = createDirectoryEntryCommandId(provider, entryName),
              deduplicationOffset = offset,
            )
        } yield Some("created directory entry.")
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
        transferContext <- scanConnection.getAppTransferContext()
        // check whether the entry already exists
        result <- store.lookupEntryByName(entryName).flatMap {
          case QueryResult(_, Some(entry)) =>
            rejectPayment(
              s"entry already exists and owned by ${entry.payload.user}.",
              transferContext,
            )
          case QueryResult(off, None) =>
            // collect the payment and create the entry
            collectPayment(offer, entryName, off, transferContext)
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
      def rejectPayment(reason: String, transferContext: v1.coin.AppTransferContext) = {
        logger.warn(s"rejecting initial subscription payment: $reason")
        val cmd = payment.contractId.exerciseSubscriptionInitialPayment_Reject(transferContext)
        connection
          .submitWithResultNoDedup(Seq(provider), Seq(), cmd)
          .map(_ => Some(s"rejected initial subscription payment: $reason"))
      }
      def collectPayment(
          context: Contract[
            directoryCodegen.DirectoryEntryContext.ContractId,
            directoryCodegen.DirectoryEntryContext,
          ],
          entryName: String,
          offset: String,
          transferContext: v1.coin.AppTransferContext,
      ) = {
        val cmd =
          contextId
            .exerciseDirectoryEntryContext_CollectInitialEntryPaymentWithSubscription(
              payment.contractId,
              transferContext,
            )
            .commands
        for {
          _ <- connection
            .submitCommands(
              actAs = Seq(provider),
              readAs = Seq.empty,
              commands = cmd.asScala.toSeq,
              commandId = createDirectoryEntryCommandId(provider, entryName),
              deduplicationOffset = offset,
            )
        } yield Some("created directory entry.")
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
        transferContext <- scanConnection.getAppTransferContext()
        // TODO(M3-90): understand what kind of assertions are worth checking here for defensive programming
        entryName = context.payload.name
        // check whether the entry already exists
        result <- store.lookupEntryByName(entryName).flatMap {
          case QueryResult(_, Some(entry)) =>
            rejectPayment(
              s"entry already exists and owned by ${entry.payload.user}.",
              transferContext,
            )
          case QueryResult(off, None) =>
            // collect the payment and create the entry
            collectPayment(context, entryName, off, transferContext)
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
      def rejectPayment(reason: String, transferContext: v1.coin.AppTransferContext) = {
        logger.warn(s"rejecting subscription payment: $reason")
        val cmd = payment.contractId.exerciseSubscriptionPayment_Reject(transferContext).commands
        connection
          .submitCommandsNoDedup(Seq(provider), Seq(), cmd.asScala.toSeq)
          .map(_ => Some(s"rejected subscription payment: $reason"))
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
          transferContext: v1.coin.AppTransferContext,
      ) = {
        val cmd =
          contextId
            .exerciseDirectoryEntryContext_CollectEntryRenewalPaymentWithSubscription(
              payment.contractId,
              entry.contractId,
              transferContext,
            )
            .commands
        for {
          _ <- connection
            .submitCommands(
              actAs = Seq(provider),
              readAs = Seq.empty,
              commands = cmd.asScala.toSeq,
              commandId = createDirectoryEntryCommandId(provider, entry.payload.name),
              deduplicationOffset = offset,
            )
        } yield Some("renewed directory entry.")
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
        transferContext <- scanConnection.getAppTransferContext()
        // TODO(M3-90): understand what kind of assertions are worth checking here for defensive programming
        entryName = context.payload.name
        // check whether the entry exists
        result <- store.lookupEntryByName(entryName).flatMap {
          case QueryResult(off, Some(entry)) =>
            // collect the payment and renew the entry
            collectPayment(context, entry, off, transferContext)
          case QueryResult(_, None) =>
            rejectPayment("entry doesn't exist.", transferContext)
        }
      } yield result
    }
  })

  registerTimeHandler(
    "handleDirectoryEntryExpiration",
    entryExpirationCheckInterval,
    connection,
  )(now => { implicit traceContext =>
    {
      store
        .listEntries()
        .map { case QueryResult(_, entries) =>
          // extract due entries
          entries.filter(e =>
            // we grant a short additional "grace period" to account for potential clock skew
            now.toInstant
              .isAfter(e.payload.expiresAt.plus(automationConfig.clockSkewAutomationDelay.duration))
          )
        }
        .flatMap(due_entries => {
          if (due_entries.isEmpty) {
            Future(Some("no entries due for expiry."))
          } else {
            // join all expire commands into one command
            val expire_commands = due_entries.flatMap(e =>
              e.contractId
                .exerciseDirectoryEntry_Expire(provider.toProtoPrimitive)
                .commands
                .asScala
                .toSeq
            )
            val due_entries_names = due_entries.map(_.payload.name).mkString(", ")
            logger.info(s"Archiving the following expired directory entries: $due_entries_names")
            connection
              .submitCommandsNoDedup(Seq(provider), Seq(), expire_commands)
              .map(_ => Some(s"archived expired entries: ${due_entries_names}"))
          }
        })
    }
  })
}
