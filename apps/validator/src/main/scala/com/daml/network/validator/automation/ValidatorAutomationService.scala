package com.daml.network.validator.automation

import akka.stream.Materializer
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class ValidatorAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    store: ValidatorStore,
    ledgerClient: CNLedgerClient,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      Map(store.key.validatorParty -> store),
      ledgerClient,
      retryProvider,
    ) {}
