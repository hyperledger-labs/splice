package com.daml.network.wallet

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.validator.admin.api.client.ValidatorConnection
import com.daml.network.wallet.admin.WalletAutomationService
import com.daml.network.wallet.admin.grpc.GrpcWalletService
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.daml.network.wallet.store.WalletStore
import com.daml.network.wallet.v0.WalletServiceGrpc
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TracerProvider
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
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
      validatorParty <- validatorConnection.getValidatorPartyId()
      svcParty <- scanConnection.getSvcPartyId()
    } yield {
      val walletStoreKey = WalletStore.Key(
        walletServiceParty = walletServiceParty,
        validatorParty = validatorParty,
        svcParty = svcParty,
      )
      val walletStore = WalletStore(walletStoreKey, storage, loggerFactory)

      adminServerRegistry.addService(
        WalletServiceGrpc.bindService(
          new GrpcWalletService(
            walletStore,
            ledgerClient,
            scanConnection,
            loggerFactory = loggerFactory,
          ),
          ec,
        )
      )
      val automation = new WalletAutomationService(
        walletStore,
        ledgerClient,
        loggerFactory,
        timeouts,
      )
      WalletApp.State(
        automation,
        storage,
        walletStore,
        scanConnection,
        validatorConnection,
        loggerFactory.getTracedLogger(WalletApp.State.getClass),
      )
    }
  }

  override val ports =
    Map("admin" -> config.adminApi.port)

  override val requiredTemplates = Set(walletCodegen.WalletAppInstall)
}

object WalletApp {
  case class State(
      automation: WalletAutomationService,
      storage: Storage,
      walletStore: WalletStore,
      scanConnection: ScanConnection,
      validatorConnection: ValidatorConnection,
      logger: TracedLogger,
  ) extends AutoCloseable {
    override def close() =
      Lifecycle.close(
        automation,
        storage,
        walletStore,
        scanConnection,
        validatorConnection,
      )(logger)
  }
}
