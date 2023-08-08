package com.daml.network.sv

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.CNNodeBootstrapBase
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.metrics.SvAppMetrics
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.*
import com.digitalasset.canton.telemetry.ConfiguredOpenTelemetry
import com.digitalasset.canton.time.*

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of an SV app.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SvAppBootstrap(
    override val name: InstanceName,
    val config: SvAppBackendConfig,
    val svAppParameters: SharedCNNodeAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: SvAppMetrics,
    storageFactory: StorageFactory,
    loggerFactory: NamedLoggerFactory,
    futureSupervisor: FutureSupervisor,
    configuredOpenTelemetry: ConfiguredOpenTelemetry,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CNNodeBootstrapBase[
      SvApp,
      SvAppBackendConfig,
      SharedCNNodeAppParameters,
    ](
      name,
      svAppParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      configuredOpenTelemetry,
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
          futureSupervisor,
          metrics,
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
      svConfig: SvAppBackendConfig,
      svAppParameters: SharedCNNodeAppParameters,
      clock: Clock,
      svMetrics: SvAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      loggerFactory: NamedLoggerFactory,
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
          futureSupervisor,
          configuredOpenTelemetry,
        )
      )
      .leftMap(_.toString)
}
