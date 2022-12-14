package com.daml.network.wallet

import akka.stream.Materializer
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinRetries}
import com.daml.network.util.HasHealth
import com.daml.network.wallet.automation.UserWalletAutomationService
import com.daml.network.wallet.config.TreasuryConfig
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

/** A service managing the treasury, automation, and store for an end-user's wallet. */
class UserWalletService(
    ledgerClient: CoinLedgerClient,
    key: UserWalletStore.Key,
    walletManager: UserWalletManager,
    automationConfig: AutomationConfig,
    clock: Clock,
    treasuryConfig: TreasuryConfig,
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
    s"TreasuryService_${CoinLedgerConnection.sanitizeUserIdToLedgerString(key.endUserName)}"
  )

  val treasury: TreasuryService = new TreasuryService(
    connection,
    treasuryConfig,
    clock,
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
    clock,
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
