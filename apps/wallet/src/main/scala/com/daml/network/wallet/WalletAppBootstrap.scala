package com.daml.network.wallet

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.wallet.config.WalletAppBackendConfig
import com.daml.network.wallet.metrics.WalletAppMetrics
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.*
import com.digitalasset.canton.time.*

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.Future
import com.digitalasset.canton.telemetry.ConfiguredOpenTelemetry

/** Class used to orchestrate the starting/initialization of Wallet apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class WalletAppBootstrap(
    override val name: InstanceName,
    val config: WalletAppBackendConfig,
    val walletAppBackendParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: WalletAppMetrics,
    storageFactory: StorageFactory,
    loggerFactory: NamedLoggerFactory,
    writeHealthDumpToFile: HealthDumpFunction,
    futureSupervisor: FutureSupervisor,
    configuredOpenTelemetry: ConfiguredOpenTelemetry,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      WalletApp,
      WalletAppBackendConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      walletAppBackendParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      writeHealthDumpToFile,
      configuredOpenTelemetry,
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.fromEither(
      Right(
        // WalletApp constructor spawns Future that
        // performs actual initialization in the background.
        new WalletApp(
          name,
          config,
          walletAppBackendParameters,
          storage,
          clock,
          loggerFactory,
          tracerProvider,
          futureSupervisor,
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
      walletConfig: WalletAppBackendConfig,
      coinAppParameters: SharedCoinAppParameters,
      clock: Clock,
      walletMetrics: WalletAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      writeHealthDumpToFile: HealthDumpFunction,
      configuredOpenTelemetry: ConfiguredOpenTelemetry,
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
          futureSupervisor,
          configuredOpenTelemetry,
        )
      )
      .leftMap(_.toString)
}
