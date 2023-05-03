package com.daml.network.splitwell

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.CNNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.CNNodeBootstrapBaseGrpc
import com.daml.network.splitwell.config.SplitwellAppBackendConfig
import com.daml.network.splitwell.metrics.SplitwellAppMetrics
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

/** Class used to orchester the starting/initialization of Splitwell apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SplitwellAppBootstrap(
    override val name: InstanceName,
    val config: SplitwellAppBackendConfig,
    val splitwellAppParameters: SharedCNNodeAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: SplitwellAppMetrics,
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
) extends CNNodeBootstrapBaseGrpc[
      SplitwellApp,
      SplitwellAppBackendConfig,
      SharedCNNodeAppParameters,
    ](
      name,
      config,
      splitwellAppParameters,
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
        new SplitwellApp(
          name,
          config,
          splitwellAppParameters,
          storage,
          clock,
          loggerFactory,
          tracerProvider,
          adminServerRegistry,
          futureSupervisor,
        )
      )
    )
  }

  override def isActive: Boolean = storage.isActive
}

object SplitwellAppBootstrap {
  val LoggerFactoryKeyName: String = "splitwell"

  def apply(
      name: String,
      splitwellConfig: SplitwellAppBackendConfig,
      coinAppParameters: SharedCNNodeAppParameters,
      clock: Clock,
      splitwellMetrics: SplitwellAppMetrics,
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
  ): Either[String, SplitwellAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new SplitwellAppBootstrap(
          _,
          splitwellConfig,
          coinAppParameters,
          testingConfigInternal,
          clock,
          splitwellMetrics,
          new CommunityStorageFactory(splitwellConfig.storage),
          loggerFactory,
          writeHealthDumpToFile,
          futureSupervisor,
          configuredOpenTelemetry,
        )
      )
      .leftMap(_.toString)
}
