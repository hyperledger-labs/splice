package com.daml.network.automation

import akka.stream.Materializer
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.store.CNNodeAppStore
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

abstract class CNNodeAppAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    stores: Map[PartyId, CNNodeAppStore[?, ?]],
    ledgerClient: CNLedgerClient,
    retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clock, retryProvider) {

  protected val connection = registerResource(
    ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
  )

  def newUpdateIngestionService(
      store: CNNodeAppStore[?, ?],
      domain: DomainId,
      perDomainTriggerContext: TriggerContext,
  ) =
    new UpdateIngestionService(
      store.getClass.getSimpleName,
      store.multiDomainAcsStore.ingestionSink,
      store.offset,
      domain,
      connection,
      perDomainTriggerContext.retryProvider,
      perDomainTriggerContext.loggerFactory,
      perDomainTriggerContext.timeouts,
    )

  stores.values.foreach(store => {
    registerTrigger(
      new DomainIngestionService(
        store.domains.ingestionSink,
        connection,
        triggerContext,
      )
    )
    registerTrigger(
      new OffsetIngestionService(
        store.offset.ingestionSink,
        store.domains,
        connection,
        // We want to always poll periodically and quickly even in simtime mode so we overwrite
        // the polling interval and the clock.
        triggerContext.copy(
          config = triggerContext.config.copy(
            pollingInterval = NonNegativeFiniteDuration.ofMillis(100)
          ),
          clock = new WallClock(
            triggerContext.timeouts,
            triggerContext.loggerFactory,
          ),
        ),
      )
    )
    val domainAcsOrchestrator = DomainOrchestrator(
      triggerContext,
      store.domains,
      (da, triggerContext) => newUpdateIngestionService(store, da.domainId, triggerContext),
    )
    registerTrigger(domainAcsOrchestrator)
  })
}
