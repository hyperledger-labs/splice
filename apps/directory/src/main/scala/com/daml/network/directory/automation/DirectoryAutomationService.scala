package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.config.AutomationConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
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
    ledgerClient: CNLedgerClient,
    scanConnection: ScanConnection,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      Map(store.providerParty -> store),
      ledgerClient,
      participantAdminConnection,
      retryProvider,
    ) {

  override protected def timeouts: ProcessingTimeout = processingTimeouts

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
