package com.daml.network.scan

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.CNNodeBootstrapBase
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.metrics.ScanAppMetrics
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.telemetry.ConfiguredOpenTelemetry
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.*
import com.digitalasset.canton.time.*

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Scan apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ScanAppBootstrap(
    override val name: InstanceName,
    val config: ScanAppBackendConfig,
    val scanAppParameters: SharedCNNodeAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: ScanAppMetrics,
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
      ScanApp,
      ScanAppBackendConfig,
      SharedCNNodeAppParameters,
    ](
      name,
      config,
      scanAppParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      configuredOpenTelemetry,
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.fromEither(
      Right(
        new ScanApp(
          name,
          config,
          scanAppParameters,
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

object ScanAppBootstrap {
  val LoggerFactoryKeyName: String = "scan"

  def apply(
      name: String,
      scanConfig: ScanAppBackendConfig,
      coinAppParameters: SharedCNNodeAppParameters,
      clock: Clock,
      scanMetrics: ScanAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      configuredOpenTelemetry: ConfiguredOpenTelemetry,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, ScanAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new ScanAppBootstrap(
          _,
          scanConfig,
          coinAppParameters,
          testingConfigInternal,
          clock,
          scanMetrics,
          new CommunityStorageFactory(scanConfig.storage),
          loggerFactory,
          futureSupervisor,
          configuredOpenTelemetry,
        )
      )
      .leftMap(_.toString)
}
