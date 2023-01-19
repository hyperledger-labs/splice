package com.daml.network.automation

import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.{CoinAppStore, DomainStore}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

abstract class CoinAppAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    store: CoinAppStore,
    ledgerClient: CoinLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends AutomationService(automationConfig, clock, retryProvider) {
  import CoinAppAutomationService.*

  protected val connection = registerResource(ledgerClient.connection())

  registerService(
    new AcsIngestionService(
      this.getClass.getSimpleName,
      store.acsIngestionSink,
      assertGlobalDomain(store.domains),
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  registerTrigger(
    new DomainIngestionService(
      store.domainIngestionSink,
      participantAdminConnection,
      triggerContext,
    )
  )
}

object CoinAppAutomationService {
  // TODO (#2221) delete; make multiple AcsStores depend on add/remove domains instead
  private[network] def assertGlobalDomain(
      domains: DomainStore
  )(implicit ec: ExecutionContext): () => Future[DomainId] = {
    val forceGlobal = com.digitalasset.canton.DomainAlias.tryCreate("global")
    () => domains.signalWhenConnected(forceGlobal) flatMap (_ => domains.getDomainId(forceGlobal))
  }
}
