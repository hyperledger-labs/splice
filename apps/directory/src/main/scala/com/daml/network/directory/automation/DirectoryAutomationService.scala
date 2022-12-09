package com.daml.network.directory.automation

import akka.stream.Materializer
import cats.instances.list.*
import cats.syntax.traverse.*
import com.daml.network.automation.{AcsIngestionService, AutomationService, TriggerContext}
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.config.AutomationConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AcsStore.QueryResult
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
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

  {
    val ctx = TriggerContext(automationConfig, timeouts, retryProvider, loggerFactory)

    registerNewStyleTrigger(new DirectoryInstallRequestTrigger(ctx, store, connection))

    registerNewStyleTrigger(
      new SubscriptionInitialPaymentTrigger(ctx, store, connection, scanConnection)
    )

    registerNewStyleTrigger(
      new SubscriptionPaymentTrigger(ctx, store, connection, scanConnection)
    )
  }

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
