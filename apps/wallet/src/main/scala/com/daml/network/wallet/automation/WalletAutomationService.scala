package com.daml.network.wallet.automation

import com.digitalasset.canton.DomainAlias
import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.CoinAppAutomationService
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
    globalDomain: DomainAlias,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: CoinRetries,
    implicit protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CoinAppAutomationService(
      automationConfig,
      clock,
      Map(walletManager.store.key.validatorParty -> walletManager.store),
      ledgerClient,
      participantAdminConnection,
      retryProvider,
    ) {

  registerTrigger(new WalletAppInstallTrigger(triggerContext, walletManager, globalDomain))
  registerTrigger(new OffboardUsersTrigger(triggerContext, walletManager))
}
