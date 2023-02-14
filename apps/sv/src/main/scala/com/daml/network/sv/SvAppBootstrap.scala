package com.daml.network.sv

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.{CoinNodeBootstrapBase, CoinRetries}
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.sv.metrics.SvAppMetrics
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

/** Class used to orchester the starting/initialization of an SV app.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SvAppBootstrap(
    override val name: InstanceName,
    val config: LocalSvAppConfig,
    val svAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: SvAppMetrics,
    storageFactory: StorageFactory,
    loggerFactory: NamedLoggerFactory,
    writeHealthDumpToFile: HealthDumpFunction,
    retryProvider: CoinRetries,
    futureSupervisor: FutureSupervisor,
    configuredOpenTelemetry: ConfiguredOpenTelemetry,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      SvApp,
      LocalSvAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      svAppParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      writeHealthDumpToFile,
      metrics.grpcMetrics,
      configuredOpenTelemetry,
      metrics.healthMetrics,
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.fromEither(
      Right(
        new SvApp(
          name,
          config,
          svAppParameters,
          storage,
          clock,
          loggerFactory,
          tracerProvider,
          adminServerRegistry,
          retryProvider,
          futureSupervisor,
        )
      )
    )
  }

  override def isActive: Boolean = storage.isActive
}

object SvAppBootstrap {
  val LoggerFactoryKeyName: String = "SV"

  def apply(
      name: String,
      svConfig: LocalSvAppConfig,
      svAppParameters: SharedCoinAppParameters,
      clock: Clock,
      svMetrics: SvAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      loggerFactory: NamedLoggerFactory,
      writeHealthDumpToFile: HealthDumpFunction,
      retryProvider: CoinRetries,
      futureSupervisor: FutureSupervisor,
      configuredOpenTelemetry: ConfiguredOpenTelemetry,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, SvAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new SvAppBootstrap(
          _,
          svConfig,
          svAppParameters,
          testingConfigInternal,
          clock,
          svMetrics,
          new CommunityStorageFactory(svConfig.storage),
          loggerFactory,
          writeHealthDumpToFile,
          retryProvider,
          futureSupervisor,
          configuredOpenTelemetry,
        )
      )
      .leftMap(_.toString)
}
