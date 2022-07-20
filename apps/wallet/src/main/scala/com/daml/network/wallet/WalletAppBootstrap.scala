package com.daml.network.wallet

import java.util.concurrent.ScheduledExecutorService

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.examples.v0.WalletServiceGrpc
import com.daml.network.wallet.admin.grpc.GrpcWalletService
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.daml.network.wallet.metrics.WalletAppMetrics
import com.daml.network.wallet.store.WalletAppStore
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.ledger.api.client.LedgerConnection
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

      adminServerRegistry.addService(
        WalletServiceGrpc.bindService(
          new GrpcWalletService(connection, config.damlUser, loggerFactory),
          executionContext,
        )
      )
      new WalletApp(
        config,
        walletAppParameters,
        storage,
        dummyStore,
        clock,
        loggerFactory,
      )
    }
  }

  override def isActive: Boolean = storage.isActive
}

// TODO(Arne): Do we need this factory construction?
// I think Canton only needs this for being able to generalize a community/enterprise participant with the same method
// while being able to return an `Either` to signal that the initialization failed
object WalletAppBootstrap {
  val LoggerFactoryKeyName: String = "wallet"

  trait Factory {
    def create(
        name: String,
        walletConfig: LocalWalletAppConfig,
        coinAppParameters: SharedCoinAppParameters,
        clock: Clock,
        testingTimeService: TestingTimeService,
        walletMetrics: WalletAppMetrics,
        testingConfig: TestingConfigInternal,
        futureSupervisor: FutureSupervisor,
        loggerFactory: NamedLoggerFactory,
    )(implicit
        executionContext: ExecutionContextIdlenessExecutorService,
        scheduler: ScheduledExecutorService,
        actorSystem: ActorSystem,
        executionSequencerFactory: ExecutionSequencerFactory,
    ): Either[String, WalletAppBootstrap]
  }

  object WalletFactory extends Factory {

    override def create(
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
}
