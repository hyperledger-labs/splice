package com.daml.network.scan.automation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, PackageIdResolver, RetryProvider}
import com.daml.network.scan.store.ScanStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on a CC Scan app. */
class ScanAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    ledgerClient: CNLedgerClient,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    override val store: ScanStore,
    ingestFromParticipantBegin: Boolean,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      store,
      PackageIdResolver.inferFromCoinRules(clock, store, loggerFactory),
      ledgerClient,
      retryProvider,
      ingestFromParticipantBegin,
    ) {
  registerTrigger(new ScanAggregationTrigger(store, triggerContext))
}
