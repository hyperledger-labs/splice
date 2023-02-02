package com.daml.network.automation

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.CoinAppStore
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PartyId, DomainId}
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

  protected val connection = registerResource(ledgerClient.connection())

  private[this] def registerDomainAcs(store: CoinAppStore[?, ?], domain: DomainId) = {
    val stores = store.installNewPerDomainStore(domain)
    val ingestionService = new AcsIngestionService(
      s"${this.getClass.getSimpleName}(${store.getClass.getSimpleName})",
      store.storesIngestionSink(stores),
      domain,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
    new DomainStoreLink(store, domain, ingestionService)
  }

  import com.daml.network.util.HasHealth

  private[this] final class DomainStoreLink[+A](
      store: CoinAppStore[?, ?],
      domain: DomainId,
      child: A & HasHealth & AutoCloseable,
  ) extends HasHealth
      with AutoCloseable {
    override def isHealthy = child.isHealthy
    override def close() = {
      store.uninstallPerDomainStore(domain)
      Lifecycle.close(child)(logger)
    }
  }

  stores.values.foreach(store => {
    registerTrigger(
      new DomainIngestionService(
        store.domainIngestionSink,
        participantAdminConnection,
        triggerContext,
      )
    )
    val domainAcsOrchestrator = new DomainOrchestrator(
      triggerContext,
      store.domains,
      da => registerDomainAcs(store, da.domainId),
    )
    registerTrigger(domainAcsOrchestrator)
  })
}
