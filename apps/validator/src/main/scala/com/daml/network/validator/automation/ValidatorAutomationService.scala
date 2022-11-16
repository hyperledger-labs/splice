package com.daml.network.validator.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class ValidatorAutomationService(
    store: ValidatorStore,
    ledgerClient: CoinLedgerClient,
    clockConfig: ClockConfig,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(clockConfig, retryProvider) {

  private val connection = ledgerClient.connection(this.getClass.getSimpleName)

  registerService(
    new AcsIngestionService(
      store.getClass.getSimpleName,
      store.acsIngestionSink,
      connection,
      loggerFactory,
      timeouts,
    )
  )

}
