package com.daml.network.automation

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.CoinAppStore
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

abstract class CoinAppAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    store: CoinAppStore[?, ?],
    ledgerClient: CoinLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clock, retryProvider) {

  protected val connection = registerResource(ledgerClient.connection())

  private[this] def registerDomainAcs(domain: DomainId) = {
    val stores = store.installNewPerDomainStore(domain)
    val ingestionService = new AcsIngestionService(
      this.getClass.getSimpleName,
      store.storesIngestionSink(stores),
      domain,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
    new DomainStoreLink(domain, ingestionService)
  }

  import com.daml.network.util.HasHealth

  private[this] final class DomainStoreLink[+A](
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

  registerTrigger(
    new DomainIngestionService(
      store.domainIngestionSink,
      participantAdminConnection,
      triggerContext,
    )
  )

  private[this] val domainAcsOrchestrator =
    new DomainOrchestrator(triggerContext, store.domains, da => registerDomainAcs(da.domainId))

  registerTrigger(domainAcsOrchestrator)
}
