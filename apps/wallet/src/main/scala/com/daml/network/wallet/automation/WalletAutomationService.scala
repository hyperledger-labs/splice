package com.daml.network.wallet.automation

import akka.stream.Materializer
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
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
    ledgerClient: CNLedgerClient,
    retryProvider: RetryProvider,
    implicit protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      Map(walletManager.store.key.validatorParty -> walletManager.store),
      ledgerClient,
      retryProvider,
    ) {

  registerTrigger(new WalletAppInstallTrigger(triggerContext, walletManager))
  registerTrigger(new OffboardUsersTrigger(triggerContext, walletManager))
}
