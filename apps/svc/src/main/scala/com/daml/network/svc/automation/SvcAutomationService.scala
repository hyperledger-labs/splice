package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.svc.config.SvcAppBackendConfig
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvcAutomationService(
    clock: Clock,
    config: SvcAppBackendConfig,
    store: SvcStore,
    ledgerClient: CNLedgerClient,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      config.automation,
      clock,
      Map(store.svcParty -> store),
      ledgerClient,
      retryProvider,
    ) {}
