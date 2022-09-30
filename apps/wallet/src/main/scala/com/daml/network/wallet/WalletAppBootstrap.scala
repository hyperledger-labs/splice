package com.daml.network.wallet

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerConnection, CoinNodeBootstrapBase}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.validator.admin.api.client.ValidatorConnection
import com.daml.network.store.AppCoinStore
import com.daml.network.wallet.admin.WalletAutomationService
import com.daml.network.wallet.admin.grpc.GrpcWalletService
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.daml.network.wallet.metrics.WalletAppMetrics
import com.daml.network.wallet.store.{WalletAppPartyStore, WalletAppRequestStore}
import com.daml.network.wallet.v0.WalletServiceGrpc
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._

import java.util.concurrent.ScheduledExecutorService
import scala.annotation.nowarn
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Wallet apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class WalletAppBootstrap(
    override val name: InstanceName,
    val config: LocalWalletAppConfig,
    val walletAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: WalletAppMetrics,
    storageFactory: StorageFactory,
    parentLogger: NamedLoggerFactory,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    @nowarn("cat=unused")
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      WalletApp,
      LocalWalletAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      walletAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(WalletAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    val coinStore = AppCoinStore(storage, loggerFactory)
    val partyStore = WalletAppPartyStore(storage, loggerFactory)
    val store = WalletAppRequestStore(storage, loggerFactory)

    val ledgerClient =
      createLedgerClient(config.remoteParticipant, walletAppParameters.processingTimeouts)

    val scanConnection: ScanConnection =
      new ScanConnection(
        config.remoteScan.clientAdminApi,
        walletAppParameters.processingTimeouts,
        loggerFactory,
      )

    val validatorConnection: ValidatorConnection =
      new ValidatorConnection(
        config.validator.clientAdminApi,
        walletAppParameters.processingTimeouts,
        loggerFactory,
      )

    val connection = ledgerClient.connection("SvcAppBootstrap")

    val walletApp = for {
      walletServicePartyId <- connection.retryLedgerApi(
        connection.getPrimaryParty(config.serviceUser),
        CoinLedgerConnection.RetryOnUserManagementError,
      )
      validatorParty <- validatorConnection.getValidatorPartyId()
      _ = logger.info(s"Got primary party of wallet service user: $walletServicePartyId")
    } yield {
      adminServerRegistry.addService(
        WalletServiceGrpc.bindService(
          new GrpcWalletService(
            coinStore,
            store,
            ledgerClient,
            scanConnection,
            validatorParty = validatorParty,
            walletServiceParty = walletServicePartyId,
            loggerFactory = loggerFactory,
          ),
          executionContext,
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
      new WalletApp(
        config,
        walletAppParameters,
        storage,
        partyStore,
        automation,
        ledgerClient,
        scanConnection,
        validatorConnection,
        clock,
        loggerFactory,
      )
    }

    // TODO(i447): more robust retry + finding out where exceptions (e.g. io.grpc.StatusRuntimeException) disappear too
    EitherT(
      walletApp
        .recover { err =>
          logger.error(s"Wallet initialization failed with $err")
          sys.exit(1)
        }
        .map(Right(_)): Future[Either[String, WalletApp]]
    )
  }

  override def isActive: Boolean = storage.isActive
}

object WalletAppBootstrap {
  val LoggerFactoryKeyName: String = "wallet"
  def apply(
      name: String,
      walletConfig: LocalWalletAppConfig,
      coinAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      walletMetrics: WalletAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, WalletAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new WalletAppBootstrap(
          _,
          walletConfig,
          coinAppParameters,
          testingConfigInternal,
          clock,
          walletMetrics,
          new CommunityStorageFactory(walletConfig.storage),
          loggerFactory,
        )
      )
      .leftMap(_.toString)
}
