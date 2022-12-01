package com.daml.network.wallet

import akka.stream.Materializer
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinRetries}
import com.daml.network.util.HasHealth
import com.daml.network.wallet.automation.UserWalletAutomationService
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.EndUserTreasuryService
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

/** A service managing the treasury, automation, and store for an end-user's wallet. */
class UserWalletService(
    ledgerClient: CoinLedgerClient,
    key: UserWalletStore.Key,
    walletManager: UserWalletManager,
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    storage: Storage,
    retryProvider: CoinRetries,
    loggerFactory0: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer)
    extends FlagCloseable
    with NamedLogging
    with HasHealth {

  override protected val loggerFactory: NamedLoggerFactory =
    loggerFactory0.append("user", key.endUserName)

  val store: UserWalletStore = UserWalletStore(key, storage, loggerFactory, timeouts)

  private val connection = ledgerClient.connection(
    s"EndUserTreasuryService_${CoinLedgerConnection.sanitizeUserIdToLedgerString(key.endUserName)}"
  )

  val treasury: EndUserTreasuryService = new EndUserTreasuryService(
    connection,
    clockConfig,
    store,
    walletManager,
    retryProvider,
    loggerFactory,
    timeouts,
  )

  private val automation = new UserWalletAutomationService(
    store,
    treasury,
    ledgerClient,
    automationConfig,
    clockConfig,
    retryProvider,
    loggerFactory,
    timeouts,
  )

  override def isHealthy: Boolean = automation.isHealthy && treasury.isHealthy

  override def onClosed(): Unit = {
    automation.close()
    treasury.close()
    store.close()
    connection.close()
    super.onClosed()
  }
}
