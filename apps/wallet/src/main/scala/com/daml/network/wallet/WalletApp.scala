package com.daml.network.wallet

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.auth.AuthInterceptor
import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.validator.admin.api.client.ValidatorConnection
import com.daml.network.wallet.admin.grpc.GrpcWalletService
import com.daml.network.wallet.automation.WalletAutomationService
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.daml.network.wallet.store.WalletStore
import com.daml.network.wallet.treasury.TreasuryServices
import com.daml.network.wallet.v0.WalletServiceGrpc
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TracerProvider
import io.grpc.ServerInterceptors
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Wallet app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class WalletApp(
    override val name: InstanceName,
    val config: LocalWalletAppConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    adminServerRegistry: CantonMutableHandlerRegistry,
    retryProvider: CoinRetries,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    mat: Materializer,
    tracer: Tracer,
) extends CoinNode[WalletApp.State](
      config.serviceUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      CoinRetries(loggerFactory),
    ) {

  override def initialize(
      ledgerClient: CoinLedgerClient,
      walletServiceParty: PartyId,
  ): Future[WalletApp.State] = {
    for {
      scanConnection <- Future {
        new ScanConnection(
          config.remoteScan.clientAdminApi,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )
      }
      validatorConnection <- Future {
        new ValidatorConnection(
          config.validator.clientAdminApi,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )
      }
      validatorUserInfo <- retryProvider.retryForAutomationWithUncleanShutdown(
        "getValidatorPartyId",
        validatorConnection.getValidatorUserInfo(),
        this,
      )
      svcParty <- retryProvider.retryForAutomationWithUncleanShutdown(
        "getSvcPartyId",
        scanConnection.getSvcPartyId(),
        this,
      )
    } yield {
      val walletStoreKey = WalletStore.Key(
        walletServiceParty = walletServiceParty,
        validatorParty = validatorUserInfo.primaryParty,
        validatorUserName = validatorUserInfo.userName,
        svcParty = svcParty,
      )
      val walletStore =
        WalletStore(walletStoreKey, storage, loggerFactory, coinAppParameters.processingTimeouts)
      val treasuries =
        new TreasuryServices(
          ledgerClient.connection("TreasuryServices"),
          walletStore,
          retryProvider,
          loggerFactory,
          timeouts,
        )

      adminServerRegistry
        .addService(
          ServerInterceptors.intercept(
            WalletServiceGrpc.bindService(
              new GrpcWalletService(
                walletStore,
                treasuries,
                ledgerClient,
                loggerFactory,
                retryProvider,
              ),
              ec,
            ),
            new AuthInterceptor(loggerFactory),
          )
        )
        .discard

      val automation = new WalletAutomationService(
        walletStore,
        treasuries,
        ledgerClient,
        retryProvider = retryProvider,
        loggerFactory,
        timeouts,
      )
      WalletApp.State(
        automation,
        storage,
        walletStore,
        treasuries,
        scanConnection,
        validatorConnection,
        loggerFactory.getTracedLogger(WalletApp.State.getClass),
      )
    }
  }

  override lazy val ports =
    Map("admin" -> config.adminApi.port)

  override lazy val requiredTemplates = Set(walletCodegen.WalletAppInstall)
}

object WalletApp {
  case class State(
      automation: WalletAutomationService,
      storage: Storage,
      walletStore: WalletStore,
      treasuryServices: TreasuryServices,
      scanConnection: ScanConnection,
      validatorConnection: ValidatorConnection,
      logger: TracedLogger,
  ) extends AutoCloseable {
    override def close(): Unit =
      Lifecycle.close(
        automation,
        storage,
        walletStore,
        treasuryServices,
        scanConnection,
        validatorConnection,
      )(logger)
  }
}
