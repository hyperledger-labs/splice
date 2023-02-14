package com.daml.network.svc

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.{CoinNodeBootstrapBase, CoinRetries}
import com.daml.network.svc.config.SvcAppBackendConfig
import com.daml.network.svc.metrics.SvcAppMetrics
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.telemetry.{ConfiguredOpenTelemetry}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.*
import com.digitalasset.canton.time.*

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of an SVC app.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SvcAppBootstrap(
    override val name: InstanceName,
    val config: SvcAppBackendConfig,
    val svcAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: SvcAppMetrics,
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
      SvcApp,
      SvcAppBackendConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      svcAppParameters,
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
        new SvcApp(
          name,
          config,
          svcAppParameters,
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

object SvcAppBootstrap {
  val LoggerFactoryKeyName: String = "SVC"

  def apply(
      name: String,
      svcConfig: SvcAppBackendConfig,
      svcAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      svcMetrics: SvcAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      writeHealthDumpToFile: HealthDumpFunction,
      retryProvider: CoinRetries,
      configuredOpenTelemetry: ConfiguredOpenTelemetry,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, SvcAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new SvcAppBootstrap(
          _,
          svcConfig,
          svcAppParameters,
          testingConfigInternal,
          clock,
          svcMetrics,
          new CommunityStorageFactory(svcConfig.storage),
          loggerFactory,
          writeHealthDumpToFile,
          retryProvider,
          futureSupervisor,
          configuredOpenTelemetry,
        )
      )
      .leftMap(_.toString)
}
