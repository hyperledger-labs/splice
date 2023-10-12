package com.daml.network.automation

import akka.stream.Materializer
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{
  CNLedgerClient,
  CNLedgerConnection,
  PackageIdResolver,
  RetryProvider,
}
import com.daml.network.store.{CNNodeAppStore, CNNodeAppStoreWithIngestion}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** A class that wires up a single store with an ingestion service, and provides a suitable
  * context for registering triggers against that store.
  */
abstract class CNNodeAppAutomationService[Store <: CNNodeAppStore[?, ?]](
    automationConfig: AutomationConfig,
    clock: Clock,
    override val store: Store,
    packageIdResolver: PackageIdResolver,
    ledgerClient: CNLedgerClient,
    retryProvider: RetryProvider,
    ingestFromParticipantBegin: Boolean = false,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clock, retryProvider)
    with CNNodeAppStoreWithIngestion[Store] {

  override val connection: CNLedgerConnection =
    ledgerClient.connection(
      this.getClass.getSimpleName,
      loggerFactory,
      packageIdResolver,
      completionOffsetCallback,
    )

  private def completionOffsetCallback(offset: String): Future[Unit] =
    store.multiDomainAcsStore.signalWhenIngestedOrShutdown(offset)(TraceContext.empty)

  registerService(
    new UpdateIngestionService(
      store.getClass.getSimpleName,
      store.multiDomainAcsStore.ingestionSink,
      connection,
      triggerContext.retryProvider,
      triggerContext.loggerFactory,
      ingestFromParticipantBegin,
    )
  )

  registerTrigger(
    new DomainIngestionService(
      store.domains.ingestionSink,
      connection,
      // We want to always poll periodically and quickly even in simtime mode so we overwrite
      // the polling interval and the clock.
      triggerContext.copy(
        config = triggerContext.config.copy(
          pollingInterval = NonNegativeFiniteDuration.ofSeconds(1)
        ),
        clock = new WallClock(
          triggerContext.timeouts,
          triggerContext.loggerFactory,
        ),
      ),
    )
  )
}
