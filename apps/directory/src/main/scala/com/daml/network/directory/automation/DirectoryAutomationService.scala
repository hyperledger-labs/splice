package com.daml.network.directory.automation

import akka.stream.Materializer
import cats.instances.list.*
import cats.syntax.traverse.*
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
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
  private val renewalDuration = new RelTime(
    // 1 day, so subscriptions can be payed between day 89 and day 90
    24 * 60 * 60 * 1_000_000L
  )
  private val entryLifetime = new RelTime(
    // 90 days
    90 * 24 * 60 * 60 * 1_000_000L
  )

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

  registerTrigger(
    "handleDirectoryInstallRequest",
    store.acs.streamContracts(directoryCodegen.DirectoryInstallRequest.COMPANION),
  )((req, logger) =>
    implicit traceContext => {
      val user = PartyId.tryFromProtoPrimitive(req.payload.user)
      store.lookupInstallByUser(user).flatMap {
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

  registerTrigger(
    "handleSubscriptionInitialPayments",
    store.acs.streamContracts(subsCodegen.SubscriptionInitialPayment.COMPANION),
  )((payment, logger) => { implicit traceContext =>
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
            .exerciseDirectoryEntryContext_CollectInitialEntryPayment(
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
        context <- store.acs
          .lookupContractById(directoryCodegen.DirectoryEntryContext.COMPANION)(contextId)
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

  registerTrigger(
    "handleSubscriptionPayments",
    store.acs.streamContracts(subsCodegen.SubscriptionPayment.COMPANION),
  )((payment, logger) => { implicit traceContext =>
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
            .exerciseDirectoryEntryContext_CollectEntryRenewalPayment(
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
        context <- store.acs
          .lookupContractById(directoryCodegen.DirectoryEntryContext.COMPANION)(contextId)
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

  registerPollingTrigger(
    "handleDirectoryEntryExpiration",
    automationConfig.pollingInterval,
    connection,
  )((now, logger) => { implicit traceContext =>
    {
      store
        .listEntries("", 50)
        // TODO(M3-83): At the moment, we just take the first 50 results, but if there are more active entries - those will never get expired here.
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
            // TODO(#1882) Avoid unlimited batching
            connection
              .submitCommandsNoDedup(Seq(provider), Seq(), expire_commands)
              .map(_ => Some(s"archived expired entries: ${due_entries_names}"))
          }
        })
    }
  })

  registerPollingTrigger(
    "handleDirectorySubscriptionExpiration",
    automationConfig.pollingInterval,
    connection,
  )((now, logger) => { implicit traceContext =>
    for {
      allSubscriptions <- store.acs.listContracts(subsCodegen.SubscriptionIdleState.COMPANION)
      dueSubscriptions =
        allSubscriptions.value.filter(e =>
          // we grant a short additional "grace period" to account for potential clock skew
          now.toInstant
            .isAfter(
              e.payload.nextPaymentDueAt.plus(automationConfig.clockSkewAutomationDelay.duration)
            )
        )
      // Filter to only those subscriptions that have a corresponding DirectoryEntryContext
      directorySubscriptions <- dueSubscriptions.toList
        .traverse { subscription =>
          store.acs
            .lookupContractById(directoryCodegen.DirectoryEntryContext.COMPANION)(
              directoryCodegen.DirectoryEntryContext.ContractId.unsafeFromInterface(
                subscription.payload.subscriptionData.context
              )
            )
            .map(context => (subscription, context.value))
        }
        .map(_.collect { case (subscription, Some(context)) =>
          context.payload.name -> subscription
        })
      result <-
        if (directorySubscriptions.isEmpty) {
          Future.successful(Some("No subscriptions due for expiry"))
        } else {
          val expireCommands = directorySubscriptions.flatMap { case (_, sub) =>
            sub.contractId
              .exerciseSubscriptionIdleState_ExpireSubscription(provider.toProtoPrimitive)
              .commands
              .asScala
              .toSeq
          }

          val subscriptionEntries = directorySubscriptions.map(_._1).mkString(", ")
          logger.info(s"Expiring subscriptions for directory entries: $subscriptionEntries")
          // TODO(#1882) Avoid unlimited batching
          connection
            .submitCommandsNoDedup(Seq(provider), Seq(), expireCommands)
            .map(_ => Some(s"Archived subscriptions for directory entries $subscriptionEntries"))
        }
    } yield result
  })
}
