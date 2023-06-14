package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.config.AutomationConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an directory app. */
class DirectoryAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    store: DirectoryStore,
    ledgerClient: CNLedgerClient,
    scanConnection: ScanConnection,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      store,
      ledgerClient,
      retryProvider,
    ) {

  registerTrigger(new DirectoryInstallRequestTrigger(triggerContext, store, connection))

  registerTrigger(
    new SubscriptionInitialPaymentTrigger(triggerContext, store, connection, scanConnection)
  )
  registerTrigger(
    new SubscriptionPaymentTrigger(triggerContext, store, connection, scanConnection)
  )
  registerTrigger(
    new ExpiredDirectoryEntryTrigger(triggerContext, store, connection)
  )
  registerTrigger(
    new ExpiredDirectorySubscriptionTrigger(triggerContext, store, connection)
  )
}
