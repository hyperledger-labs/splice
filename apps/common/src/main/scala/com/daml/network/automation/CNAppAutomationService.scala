package com.daml.network.automation

import akka.stream.Materializer
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.store.CNNodeAppStore
import com.digitalasset.canton.time.Clock
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
      domain,
      connection,
      perDomainTriggerContext.retryProvider,
      perDomainTriggerContext.loggerFactory,
      perDomainTriggerContext.timeouts,
    )

  stores.values.foreach(store => {
    registerTrigger(
      new DomainIngestionService(
        store.domainIngestionSink,
        connection,
        triggerContext,
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
