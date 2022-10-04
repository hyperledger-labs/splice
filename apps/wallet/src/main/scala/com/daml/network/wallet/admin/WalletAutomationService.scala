package com.daml.network.wallet.admin

import akka.stream.Materializer
import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.store.AppCoinStore
import com.daml.network.wallet.store.{WalletAppRequestStore, WalletStore}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Manages background automation that runs on an Wallet app.
  */
class WalletAutomationService(
    coinStore: AppCoinStore,
    walletStore: WalletStore,
    appRequestStore: WalletAppRequestStore,
    ledgerClient: CoinLedgerClient,
    loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
    mat: Materializer,
) extends LedgerAutomationServiceOrchestrator(loggerFactory)(
      ec,
      tracer,
    ) {
  override protected def timeouts: ProcessingTimeout = processingTimeouts

  val coinIngestion = new AtomicReference(
    createService("walletCoinIngestionService", ledgerClient, Seq.empty) { _ =>
      new CoinIngestionService(coinStore, appRequestStore, loggerFactory)
    }
  )

  // TODO(#929): remove this indirection once the whole wallet uses the new store and handler pattern
  val newWalletAutomationService = new NewWalletAutomationService(
    walletStore,
    parties =>
      Future {
        coinIngestion.updateAndGet(s => updateReadAs(s, parties))
        ()
      },
    ledgerClient,
    loggerFactory,
    timeouts,
  )

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq[AsyncOrSyncCloseable](
    SyncCloseable(
      "Wallet automation services",
      Lifecycle.close(coinIngestion.get, newWalletAutomationService)(
        logger
      ),
    )
  )
}
