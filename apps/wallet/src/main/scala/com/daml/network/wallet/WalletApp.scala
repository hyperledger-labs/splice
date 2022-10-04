package com.daml.network.wallet

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerConnection, CoinLedgerClient, CoinNode}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AppCoinStore
import com.daml.network.validator.admin.api.client.ValidatorConnection
import com.daml.network.wallet.admin.{WalletAutomationService}
import com.daml.network.wallet.admin.grpc.GrpcWalletService
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.daml.network.wallet.store.{WalletAppPartyStore, WalletAppRequestStore}
import com.daml.network.wallet.v0.WalletServiceGrpc
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.{Clock, HasUptime}
import com.digitalasset.canton.topology.UniqueIdentifier
import com.digitalasset.canton.tracing.TracerProvider
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
) extends CoinNode[WalletApp.State](coinAppParameters)
    with HasUptime {

  override def initialize(): Future[WalletApp.State] =
    for {
      coinStore <- Future.successful(AppCoinStore(storage, loggerFactory))
      partyStore = WalletAppPartyStore(storage, loggerFactory)
      store = WalletAppRequestStore(storage, loggerFactory)
      ledgerClient = CoinLedgerClient(
        config.remoteParticipant.ledgerApi,
        ApiTypes.ApplicationId(name.unwrap),
        CommandClientConfiguration.default,
        config.remoteParticipant.token,
        coinAppParameters.processingTimeouts,
        loggerFactory,
        tracerProvider,
      )
      scanConnection =
        new ScanConnection(
          config.remoteScan.clientAdminApi,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )

      validatorConnection =
        new ValidatorConnection(
          config.validator.clientAdminApi,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )

      connection = ledgerClient.connection("SvcAppBootstrap")

      walletServicePartyId <-
        connection.retryLedgerApi(
          connection.getPrimaryParty(config.serviceUser),
          CoinLedgerConnection.RetryOnUserManagementError,
        )

      validatorPartyId <- validatorConnection.getValidatorPartyId()
    } yield {

      adminServerRegistry.addService(
        WalletServiceGrpc.bindService(
          new GrpcWalletService(
            coinStore,
            store,
            ledgerClient,
            scanConnection,
            validatorParty = validatorPartyId,
            walletServiceParty = walletServicePartyId,
            loggerFactory = loggerFactory,
          ),
          ec,
        )
      )
      val automation = new WalletAutomationService(
        coinStore,
        partyStore,
        store,
        config.serviceUser,
        walletServicePartyId,
        ledgerClient,
        loggerFactory,
        timeouts,
      )
      WalletApp.State(
        automation,
        storage,
        coinStore,
        partyStore,
        store,
        ledgerClient,
        scanConnection,
        validatorConnection,
        loggerFactory.getTracedLogger(WalletApp.State.getClass),
      )
    }

  // TODO(i736): fork or generalize status definition.
  override def status: Future[NodeStatus.Status] = {
    val status = SimpleStatus(
      UniqueIdentifier.tryFromProtoPrimitive("example::default"),
      uptime(),
      Map("admin" -> config.adminApi.port),
      storage.isActive,
      TopologyQueueStatus(0, 0, 0),
    )
    Future.successful(status)
  }
}

object WalletApp {
  case class State(
      automation: WalletAutomationService,
      storage: Storage,
      coinStore: AppCoinStore,
      partyStore: WalletAppPartyStore,
      store: WalletAppRequestStore,
      ledgerClient: CoinLedgerClient,
      scanConnection: ScanConnection,
      validatorConnection: ValidatorConnection,
      logger: TracedLogger,
  ) extends AutoCloseable {
    override def close() =
      Lifecycle.close(
        automation,
        storage,
        partyStore,
        ledgerClient,
        scanConnection,
        validatorConnection,
      )(logger)

  }
}
