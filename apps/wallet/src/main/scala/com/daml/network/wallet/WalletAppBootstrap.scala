package com.daml.network.wallet

import java.util.concurrent.ScheduledExecutorService

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.wallet.admin.grpc.GrpcWalletService
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.daml.network.wallet.metrics.WalletAppMetrics
import com.daml.network.wallet.store.WalletAppStore
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
    @nowarn("cat=unused")
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
    EitherT.rightT[Future, String] {
      val dummyStore = WalletAppStore(storage, loggerFactory)

      val connection =
        createLedgerConnection(config.remoteParticipant, walletAppParameters.processingTimeouts)

      val scanConnection: ScanConnection =
        ScanConnection.fromClientAdminApi(
          config.remoteScan.clientAdminApi,
          walletAppParameters.processingTimeouts,
          loggerFactory,
        )

      adminServerRegistry.addService(
        WalletServiceGrpc.bindService(
          new GrpcWalletService(connection, scanConnection, config.damlUser, loggerFactory),
          executionContext,
        )
      )
      new WalletApp(
        config,
        walletAppParameters,
        storage,
        dummyStore,
        scanConnection,
        clock,
        loggerFactory,
      )
    }
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
