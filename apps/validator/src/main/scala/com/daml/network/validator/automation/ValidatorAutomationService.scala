package com.daml.network.validator.automation

import akka.stream.Materializer
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.automation.{OffboardUsersTrigger, WalletAppInstallTrigger}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class ValidatorAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    walletManager: UserWalletManager,
    store: ValidatorStore,
    scanConnection: ScanConnection,
    ledgerClient: CNLedgerClient,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      Map(store.key.validatorParty -> store),
      ledgerClient,
      retryProvider,
    ) {
  registerTrigger(new WalletAppInstallTrigger(triggerContext, walletManager))
  registerTrigger(new OffboardUsersTrigger(triggerContext, walletManager))
  if (automationConfig.enableAutomaticValidatorTrafficBalanceTopup)
    registerTrigger(
      new TopupValidatorTrafficBalanceTrigger(triggerContext, walletManager, store, scanConnection)
    )
}
