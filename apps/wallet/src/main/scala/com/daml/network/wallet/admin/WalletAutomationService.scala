// TODO(#790): move to 'wallet.automation'
package com.daml.network.wallet.admin

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Manages background automation that runs on an Wallet app.
  */
class WalletAutomationService(
    walletStore: WalletStore,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(retryProvider) {

  private val connection = ledgerClient.connection(this.getClass.getSimpleName)

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
  registerRequestHandler("WalletAppInstall", walletStore.streamInstalls)(
    install => install.toString,
    (install, traceContext) =>
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
          case None =>
            "onboarded wallet end-user"
          case Some(_) =>
            logger.warn(
              s"Unexpected duplicate on-boarding of wallet user '$endUserName'"
              // TODO(#790): how would we enable the implicit passing of the traceContext?
            )(traceContext)
            autoCloseable.close()
            "skipped duplicate on-boarding wallet end-user"
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
