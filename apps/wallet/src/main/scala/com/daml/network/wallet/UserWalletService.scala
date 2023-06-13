package com.daml.network.wallet

import akka.stream.Materializer
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, CNLedgerConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.HasHealth
import com.daml.network.wallet.automation.UserWalletAutomationService
import com.daml.network.wallet.config.TreasuryConfig
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

/** A service managing the treasury, automation, and store for an end-user's wallet. */
class UserWalletService(
    ledgerClient: CNLedgerClient,
    globalDomain: DomainAlias,
    key: UserWalletStore.Key,
    walletManager: UserWalletManager,
    automationConfig: AutomationConfig,
    clock: Clock,
    treasuryConfig: TreasuryConfig,
    storage: Storage,
    retryProvider: RetryProvider,
    loggerFactory0: NamedLoggerFactory,
    scanConnection: ScanConnection,
    override protected val timeouts: ProcessingTimeout,
)(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer)
    extends FlagCloseable
    with NamedLogging
    with HasHealth {

  override protected val loggerFactory: NamedLoggerFactory =
    loggerFactory0.append("user", key.endUserName)

  val store: UserWalletStore =
    UserWalletStore(
      key,
      storage,
      globalDomain,
      loggerFactory,
      // The store needs its own connection to expand tx-history entries
      ledgerClient.connection(this.getClass.getSimpleName, loggerFactory),
      retryProvider,
    )

  val treasury: TreasuryService = new TreasuryService(
    // The treasury gets its own connection, and is required to manage waiting for the store on its own.
    ledgerClient.connection(this.getClass.getSimpleName, loggerFactory),
    treasuryConfig,
    clock,
    store,
    walletManager,
    retryProvider,
    scanConnection,
    loggerFactory,
    timeouts,
  )

  private val automation = new UserWalletAutomationService(
    store,
    treasury,
    ledgerClient,
    globalDomain,
    automationConfig,
    clock,
    retryProvider,
    loggerFactory,
    timeouts,
  )

  /** The connection to use when submitting commands based on reads from the WalletStore.
    * The submission will wait for the store to ingest the effect of the command before completing the future.
    */
  val connection: CNLedgerConnection = automation.connection

  override def isHealthy: Boolean = automation.isHealthy && treasury.isHealthy

  override def onClosed(): Unit = {
    // Close treasury early, that will result in it no longer accepting new requests
    // but in-flight requests can complete. If we close the automation first,
    // a task can get stuck forever waiting for store ingestion to complete.
    treasury.close()
    automation.close()
    store.close()
    super.onClosed()
  }
}
