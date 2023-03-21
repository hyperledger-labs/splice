package com.daml.network.automation

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.CoinAppStore
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

abstract class CoinAppAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    stores: Map[PartyId, CoinAppStore[?, ?]],
    ledgerClient: CoinLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clock, retryProvider) {

  protected val connection = registerResource(
    ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
  )

  private[this] def registerDomainAcs(
      store: CoinAppStore[?, ?],
      domain: DomainId,
      perDomainTriggerContext: TriggerContext,
  ) = {
    val stores = store.installNewPerDomainStore(domain, perDomainTriggerContext.loggerFactory)
    val ingestionService = new LegacyUpdateIngestionService(
      store.getClass.getSimpleName,
      store.storesIngestionSink(stores),
      domain,
      connection,
      perDomainTriggerContext.retryProvider,
      perDomainTriggerContext.loggerFactory,
      perDomainTriggerContext.timeouts,
    )
    import com.daml.network.util.HasHealth
    new HasHealth with AutoCloseable {
      override def isHealthy = ingestionService.isHealthy

      override def close() = {
        store.uninstallPerDomainStore(domain)
        Lifecycle.close(ingestionService)(logger)
      }
    }
  }

  def newUpdateIngestionService(
      store: CoinAppStore[?, ?],
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
        participantAdminConnection,
        triggerContext,
      )
    )
    val domainAcsOrchestrator = DomainOrchestrator(
      triggerContext,
      store.domains,
      DomainOrchestrator.multipleServices(
        Seq(
          (da, triggerContext) => registerDomainAcs(store, da.domainId, triggerContext),
          (da, triggerContext) => newUpdateIngestionService(store, da.domainId, triggerContext),
        ),
        loggerFactory,
      ),
    )
    registerTrigger(domainAcsOrchestrator)
  })
}
