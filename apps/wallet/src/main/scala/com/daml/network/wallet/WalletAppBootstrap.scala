package com.daml.network.wallet

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.{CoinNodeBootstrapBase, CoinRetries}
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.daml.network.wallet.metrics.WalletAppMetrics
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.*
import com.digitalasset.canton.time.*

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
    writeHealthDumpToFile: HealthDumpFunction,
    retryProvider: CoinRetries,
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
      writeHealthDumpToFile,
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.fromEither(
      Right(
        // WalletApp constructor spawns Future that
        // performs actual initialization in the background.
        new WalletApp(
          name,
          config,
          walletAppParameters,
          storage,
          clock,
          loggerFactory,
          tracerProvider,
          adminServerRegistry,
          retryProvider,
        )
      )
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
      writeHealthDumpToFile: HealthDumpFunction,
      retryProvider: CoinRetries,
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
          writeHealthDumpToFile,
          retryProvider,
        )
      )
      .leftMap(_.toString)
}
