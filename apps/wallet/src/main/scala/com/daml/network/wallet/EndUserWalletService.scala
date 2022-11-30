package com.daml.network.wallet

import akka.stream.Materializer
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.util.JavaContract
import com.daml.network.wallet.automation.EndUserWalletAutomationService
import com.daml.network.wallet.store.EndUserWalletStore
import com.daml.network.wallet.treasury.EndUserTreasuryService
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

/** A service managing the treasury, automation, and store for an end-user's wallet. */
class EndUserWalletService(
    ledgerClient: CoinLedgerClient,
    // TODO(#1351): don't pass WalletAppInstall contract along but look it up before each batch submission to support users updating their
    // WalletAppInstall contract
    install: JavaContract[
      installCodegen.WalletAppInstall.ContractId,
      installCodegen.WalletAppInstall,
    ],
    key: EndUserWalletStore.Key,
    walletManager: EndUserWalletManager,
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    storage: Storage,
    retryProvider: CoinRetries,
    loggerFactory0: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer)
    extends FlagCloseable
    with NamedLogging {

  override protected val loggerFactory: NamedLoggerFactory =
    loggerFactory0.append("user", key.endUserName)

  val store: EndUserWalletStore = EndUserWalletStore(key, storage, loggerFactory, timeouts)

  // TODO(#1351): remove the need for this by having the automation service handle treasury requests
  private val connection = ledgerClient.connection(s"EndUserTreasuryService_${key.endUserName}")

  val treasury: EndUserTreasuryService = new EndUserTreasuryService(
    connection,
    install,
    store,
    walletManager,
    retryProvider,
    loggerFactory,
    timeouts,
  )

  private val automation = new EndUserWalletAutomationService(
    store,
    treasury,
    ledgerClient,
    automationConfig,
    clockConfig,
    retryProvider,
    loggerFactory,
    timeouts,
  )

  override def onClosed(): Unit = {
    automation.close()
    treasury.close()
    store.close()
    connection.close()
    super.onClosed()
  }
}
