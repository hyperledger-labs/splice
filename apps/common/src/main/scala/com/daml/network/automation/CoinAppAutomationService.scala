package com.daml.network.automation

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.digitalasset.canton.logging.NamedLoggerFactory
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

  protected val connection = registerResource(
    ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
  )

  private[this] def registerDomainAcs(
      store: CoinAppStore[?, ?],
      domain: DomainId,
      perDomainLoggerFactory: NamedLoggerFactory,
  ) = {
    val stores = store.installNewPerDomainStore(domain, perDomainLoggerFactory)
    val ingestionService = new AcsIngestionService(
      store.getClass.getSimpleName,
      store.storesIngestionSink(stores),
      domain,
      connection,
      retryProvider,
      perDomainLoggerFactory,
      timeouts,
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

  def newTransferIngestionService(
      store: CoinAppStore[?, ?],
      domain: DomainId,
      perDomainLoggerFactory: NamedLoggerFactory,
  ) =
    new TransferIngestionService(
      store.getClass.getSimpleName,
      store.transferStore,
      domain,
      connection,
      retryProvider,
      perDomainLoggerFactory,
      timeouts,
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
          (da, perDomainLoggerFactory) =>
            registerDomainAcs(store, da.domainId, perDomainLoggerFactory),
          (da, perDomainLoggerFactory) =>
            newTransferIngestionService(store, da.domainId, perDomainLoggerFactory),
        ),
        loggerFactory,
      ),
    )
    registerTrigger(domainAcsOrchestrator)
  })
}
