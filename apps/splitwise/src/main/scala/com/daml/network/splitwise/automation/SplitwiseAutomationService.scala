package com.daml.network.splitwise.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.store.SplitwiseStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an splitwise app. */
class SplitwiseAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    store: SplitwiseStore,
    ledgerClient: CoinLedgerClient,
    readAs: Set[PartyId],
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

  private val connection = registerResource(ledgerClient.connection("SplitwiseAutomationService"))

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

  registerNewStyleTrigger(
    new AcceptedAppPaymentRequestsTrigger(triggerContext, store, connection, scanConnection, readAs)
  )
  registerNewStyleTrigger(new SplitwiseInstallRequestTrigger(triggerContext, store, connection))
  registerNewStyleTrigger(new GroupRequestTrigger(triggerContext, store, connection))
}
