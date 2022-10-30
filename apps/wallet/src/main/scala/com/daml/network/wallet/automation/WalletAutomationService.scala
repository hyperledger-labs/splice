package com.daml.network.wallet.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.wallet.store.WalletStore
import com.daml.network.wallet.treasury.TreasuryServices
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Manages background automation that runs on an Wallet app.
  */
class WalletAutomationService(
    walletStore: WalletStore,
    treasuryServices: TreasuryServices,
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
  registerRequestHandler("WalletAppInstall", walletStore.streamInstalls)(install => {
    implicit traceContext =>
      Future {
        val endUserName = install.payload.endUserName
        val endUserParty = PartyId.tryFromPrim(install.payload.endUserParty)
        val endUserStore = walletStore.getOrCreateEndUserStore(endUserName, endUserParty, timeouts)
        val ingestionService = new AcsIngestionService(
          s"EndUserWalletStore($endUserName)",
          endUserStore.acsIngestionSink,
          connection,
          loggerFactory,
          timeouts,
        )
        treasuryServices.addOrCreateTreasuryService(install, walletStore.key, endUserStore): Unit
        registerService(ingestionServices, endUserName, ingestionService)
      }
  })

  private def registerService(
      services: TrieMap[String, AutoCloseable],
      endUserName: String,
      service: AutoCloseable,
  )(implicit tc: TraceContext): String = {
    services.putIfAbsent(endUserName, service) match {
      case None =>
        s"onboarded wallet end-user '$endUserName'"
      case Some(_) =>
        logger.warn(s"Unexpected duplicate on-boarding of wallet user '$endUserName'")
        service.close()
        s"skipped duplicate on-boarding wallet end-user '$endUserName'"
    }
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq(
      SyncCloseable(
        "EndUserIngestionServices",
        Lifecycle.close(ingestionServices.values.toSeq: _*)(logger),
      )
    ) ++ super.closeAsync()
}
