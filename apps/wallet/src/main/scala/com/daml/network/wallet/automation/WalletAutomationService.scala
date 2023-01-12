package com.daml.network.wallet.automation

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.{AcsIngestionService, AutomationService, DomainIngestionService}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation for the UserWalletManager. */
class WalletAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    walletManager: UserWalletManager,
    ledgerClient: CoinLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: CoinRetries,
    implicit protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clock, retryProvider) {
  private val connection = registerResource(ledgerClient.connection(this.getClass.getSimpleName))

  registerService(
    new AcsIngestionService(
      "WalletStore",
      walletManager.store.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  registerTrigger(
    new DomainIngestionService(
      walletManager.store.domainIngestionSink,
      participantAdminConnection,
      triggerContext,
    )
  )

  registerTrigger(new WalletAppInstallTrigger(triggerContext, walletManager))
}
