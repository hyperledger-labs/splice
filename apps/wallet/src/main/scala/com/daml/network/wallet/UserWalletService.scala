package com.daml.network.wallet

import akka.Done
import akka.stream.Materializer
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, CNLedgerConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{DisclosedContracts, HasHealth, TemplateJsonDecoder}
import com.daml.network.util.PrettyInstances.*
import com.daml.network.wallet.automation.UserWalletAutomationService
import com.daml.network.wallet.config.TreasuryConfig
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

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
    override protected[this] val retryProvider: RetryProvider,
    loggerFactory0: NamedLoggerFactory,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    close: CloseContext,
) extends RetryProvider.Has
    with FlagCloseable
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
  )

  private val receiveImportCratesF = receiveImportCrates()

  /** The connection to use when submitting commands based on reads from the WalletStore.
    * The submission will wait for the store to ingest the effect of the command before completing the future.
    */
  val connection: CNLedgerConnection = automation.connection

  override def isHealthy: Boolean =
    automation.isHealthy && treasury.isHealthy && !receiveImportCratesF.value.exists(_.isFailure)

  override def onClosed(): Unit = {
    // Close treasury early, that will result in it no longer accepting new requests
    // but in-flight requests can complete. If we close the automation first,
    // a task can get stuck forever waiting for store ingestion to complete.
    treasury.close()
    automation.close()
    store.close()
    super.onClosed()
  }

  private def receiveImportCrates() = TraceContext.withNewTraceContext(implicit tc0 => {
    val userPartyId = key.endUserParty

    def receive(): Future[Done] = for {
      domainId <- store.defaultAcsDomainIdF
      crates <- scanConnection.listImportCrates(userPartyId)
      coinRules <- scanConnection.getCoinRules()
      openRound <- scanConnection.getLatestOpenMiningRound()
      _ = logger.debug(show"Attempting to receive ${crates.size} crates for $userPartyId")
      _ <- Future.sequence(crates.map(crate => {
        logger.debug(show"Attempting to receive $crate")
        automation.connection.submitCommandsNoDedup(
          actAs = Seq(userPartyId),
          readAs = Seq(),
          commands = crate.contract.contractId
            .exerciseImportCrate_Receive(
              userPartyId.toProtoPrimitive,
              coinRules.contract.contractId,
              openRound.contract.contractId,
            )
            .commands()
            .asScala
            .toSeq,
          domainId,
          disclosedContracts = DisclosedContracts(crate, coinRules, openRound),
        )
      }))
    } yield {
      logger.info(show"Received ${crates.size} crates for $userPartyId")
      Done
    }

    retryProvider
      .retryForAutomation(
        show"receive import crates for $userPartyId",
        receive(),
        logger,
      )
      .transform {
        case scala.util.Success(_) => scala.util.Success(Done)
        case scala.util.Failure(ex) if isClosing =>
          logger.debug(
            s"Ignoring exception when receiving crates for $userPartyId, as we are shutting down",
            ex,
          )
          scala.util.Success(Done)
        case scala.util.Failure(ex) =>
          logger.error(
            s"Unexpected exception when receiving crates for $userPartyId, as we are shutting down",
            ex,
          )
          scala.util.Failure(ex)
      }
  })
}
