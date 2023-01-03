package com.daml.network.sv.automation

import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.sv.store.SvStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvAutomationService(
    clock: Clock,
    config: LocalSvAppConfig,
    store: SvStore,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    // mat: Materializer,
    tracer: Tracer,
) extends AutomationService(config.automation, clock, retryProvider) {

  private val connection = registerResource(ledgerClient.connection(this.getClass.getSimpleName))

  registerService(
    new AcsIngestionService(
      store.getClass.getSimpleName,
      store.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

}
