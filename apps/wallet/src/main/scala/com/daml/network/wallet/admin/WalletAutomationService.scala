package com.daml.network.wallet.admin

import akka.stream.Materializer
import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.store.AppCoinStore
import com.daml.network.wallet.store.{WalletAppPartyStore, WalletAppRequestStore}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.FutureUtil
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an Wallet app.
  */
class WalletAutomationService(
    coinStore: AppCoinStore,
    partyStore: WalletAppPartyStore,
    store: WalletAppRequestStore,
    serviceUser: String,
    serviceParty: PartyId,
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

  private val connection = ledgerClient.connection("WalletAutomationService")

  val coinIngestion = new AtomicReference(
    createService("walletCoinIngestionService", ledgerClient, Seq.empty) { _ =>
      new CoinIngestionService(coinStore, store, loggerFactory)
    }
  )

  val installIngestion =
    createService("walletInstallIngestionService", ledgerClient, Seq(serviceParty)) { _ =>
      new InstallIngestionService(partyStore, loggerFactory)
    }

  // Every time the set of parties for which a WalletAppInstall contract exists changes:
  // - Grant readAs rights for all of these parties to the wallet app service user
  // - Subscribe to the transaction stream of coins for all of these parties
  // Note that the app granting itself readAs rights is problematic from an auth POV,
  // and requires the app to have full admin rights on the ledger API.
  // TODO (i713): remove this workaround for missing `read-as-any-party` rights
  FutureUtil.doNotAwait(
    partyStore.getPartiesStream
      .mapAsync(1)(parties =>
        performUnlessClosingF("subscribe to new parties") {
          for {
            _ <- connection.grantUserRights(serviceUser, Seq.empty, parties)
            _ = coinIngestion.updateAndGet(s => updateReadAs(s, parties))
          } yield ()
        }.onShutdown(())
      )
      .run(),
    "WalletAutomationService handling of party updates failed",
  )

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq[AsyncOrSyncCloseable](
    SyncCloseable(
      "Wallet automation services",
      Lifecycle.close(coinIngestion.get, installIngestion)(logger),
    )
  )
}
