package com.daml.network.wallet

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.wallet.admin.WalletAutomationService
import com.daml.network.wallet.admin.grpc.GrpcWalletService
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.daml.network.wallet.metrics.WalletAppMetrics
import com.daml.network.wallet.store.WalletAppStore
import com.daml.network.wallet.util.WalletUtil
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
    val store = WalletAppStore(storage, loggerFactory)

    val ledgerClient =
      createLedgerClient(config.remoteParticipant, walletAppParameters.processingTimeouts)

    val scanConnection: ScanConnection =
      new ScanConnection(
        config.remoteScan.clientAdminApi,
        walletAppParameters.processingTimeouts,
        loggerFactory,
      )

    adminServerRegistry.addService(
      WalletServiceGrpc.bindService(
        new GrpcWalletService(
          store,
          ledgerClient,
          scanConnection,
          config.serviceUser,
          loggerFactory,
        ),
        executionContext,
      )
    )

    val connection = ledgerClient.connection("SvcAppBootstrap")

    val walletApp = for {
      walletServicePartyId <- connection.getOrAllocateParty(config.serviceUser)
      _ = logger.info(s"Allocated wallet service party $walletServicePartyId")
      _ <- connection.uploadDarFile(
        WalletUtil
      ) // TODO(i353) move away from dar upload during init
    } yield {
      val automation = new WalletAutomationService(
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
        store,
        automation,
        scanConnection,
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
