package com.daml.network.wallet.admin

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContextExecutor, Future}

/** The wallet automation service built on the shared automation and AcsStore infrastructure.
  */
class NewWalletAutomationService(
    walletStore: WalletStore,
    onOnboardedParties: Seq[PartyId] => Future[Unit],
    ledgerClient: CoinLedgerClient,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
) extends AutomationService {

  private val connection = ledgerClient.connection("NewWalletAutomationService")

  registerService(
    new AcsIngestionService(
      "WalletStore",
      walletStore.acsIngestionSink,
      connection,
      loggerFactory,
      timeouts,
    )
  )

  // TODO(i763): not handling archive events, uninstalling first party apps is not supported yet
  registerTaskHandler("WalletAppInstall", walletStore.getPartiesStream)(
    parties => s"onboarded parties: $parties",
    onOnboardedParties,
  )
}
