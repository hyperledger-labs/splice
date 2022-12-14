package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.config.AutomationConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an directory app. */
class DirectoryAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
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
) extends AutomationService(automationConfig, clock, retryProvider) {

  override protected def timeouts: ProcessingTimeout = processingTimeouts

  private val connection = registerResource(ledgerClient.connection("DirectoryAutomationService"))

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

  registerNewStyleTrigger(new DirectoryInstallRequestTrigger(triggerContext, store, connection))

  registerNewStyleTrigger(
    new SubscriptionInitialPaymentTrigger(triggerContext, store, connection, scanConnection)
  )
  registerNewStyleTrigger(
    new SubscriptionPaymentTrigger(triggerContext, store, connection, scanConnection)
  )
  registerNewStyleTrigger(
    new ExpiredDirectoryEntryTrigger(triggerContext, store, connection)
  )
  registerNewStyleTrigger(
    new ExpiredDirectorySubscriptionTrigger(triggerContext, store, connection)
  )
}
