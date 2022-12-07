package com.daml.network.wallet.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Manages background automation for the UserWalletManager. */
class WalletAutomationService(
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    walletManager: UserWalletManager,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    implicit protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clockConfig, retryProvider) {
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

  // TODO(#763): not handling archive events, uninstalling wallets without a restart is not supported yet
  registerTrigger(
    "create user wallet",
    walletManager.store.acs.streamContracts(installCodegen.WalletAppInstall.COMPANION),
  )((install, logger) => { implicit traceContext =>
    Future {
      val endUserName = install.payload.endUserName
      if (walletManager.getOrCreateUserWallet(install))
        Some(s"onboarded wallet end-user '$endUserName'")
      else {
        logger.warn(s"Unexpected duplicate on-boarding of wallet user '$endUserName'")
        Some(s"skipped duplicate on-boarding wallet end-user '$endUserName'")
      }
    }
  })
}
