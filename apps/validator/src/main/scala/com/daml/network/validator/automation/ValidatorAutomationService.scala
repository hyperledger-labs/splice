package com.daml.network.validator.automation

import akka.stream.Materializer
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, ParticipantAdminConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.validator.config.BuyExtraTrafficConfig
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.automation.{OffboardUsersTrigger, WalletAppInstallTrigger}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class ValidatorAutomationService(
    automationConfig: AutomationConfig,
    buyExtraTrafficConfig: BuyExtraTrafficConfig,
    clock: Clock,
    walletManager: UserWalletManager,
    store: ValidatorStore,
    scanConnection: ScanConnection,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      store,
      ledgerClient,
      retryProvider,
    ) {
  registerTrigger(new WalletAppInstallTrigger(triggerContext, walletManager))
  registerTrigger(new OffboardUsersTrigger(triggerContext, walletManager))
  if (automationConfig.enableAutomaticValidatorTrafficBalanceTopup)
    registerTrigger(
      new TopupValidatorTrafficBalanceTrigger(
        triggerContext,
        store,
        connection,
        participantAdminConnection,
        buyExtraTrafficConfig,
        clock,
        walletManager,
        scanConnection,
      )
    )
}
