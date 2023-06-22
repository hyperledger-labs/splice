package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.SvSvStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvSvAutomationService(
    clock: Clock,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    ledgerClient: CNLedgerClient,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      config.automation,
      clock,
      svStore,
      ledgerClient,
      retryProvider,
    ) {
  registerTrigger(new ExpireValidatorOnboardingTrigger(triggerContext, svStore, connection))
}
