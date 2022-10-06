package com.daml.network.wallet.admin

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Manages background automation that runs on an Wallet app.
  */
class WalletAutomationService(
    walletStore: WalletStore,
    ledgerClient: CoinLedgerClient,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
) extends AutomationService {

  private val connection = ledgerClient.connection("WalletAutomationService")

  registerService(
    new AcsIngestionService(
      "WalletStore",
      walletStore.acsIngestionSink,
      connection,
      loggerFactory,
      timeouts,
    )
  )

  private val ingestionServices: TrieMap[String, AutoCloseable] = TrieMap.empty

  // TODO(#763): not handling archive events, uninstalling wallets without a restart is not supported yet
  registerTaskHandler("WalletAppInstall", walletStore.streamInstalls)(
    install => s"onboarding wallet user: ${install.payload.endUserName}",
    install =>
      Future {
        val endUserName = install.payload.endUserName
        val endUserStore = walletStore.getOrCreateEndUserStore(
          endUserName,
          PartyId.tryFromPrim(install.payload.endUserParty),
        )
        val ingestionService = new AcsIngestionService(
          s"EndUserWalletStore($endUserName)",
          endUserStore.acsIngestionSink,
          connection,
          loggerFactory,
          timeouts,
        )
        val autoCloseable =
          SyncCloseable(
            s"EndUserWalletStore($endUserName) - ingestion and store",
            Lifecycle.close(ingestionService, endUserStore)(logger),
          )
        ingestionServices.putIfAbsent(endUserName, autoCloseable) match {
          case None => ()
          case Some(_) =>
            logger.warn(
              s"Unexpected duplicate onboarding of wallet user '$endUserName'"
            )
            autoCloseable.close()
        }
      },
  )

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq(
      SyncCloseable(
        "EndUserIngestionServices",
        Lifecycle.close(ingestionServices.values.toSeq: _*)(logger),
      )
    ) ++ super.closeAsync()
}
