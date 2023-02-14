package com.daml.network.validator

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits.*
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.{CoinNodeBootstrapBase, CoinRetries}
import com.daml.network.validator.config.ValidatorAppBackendConfig
import com.daml.network.validator.metrics.ValidatorAppMetrics
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

/** Class used to orchester the starting/initialization of Validator node.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ValidatorAppBootstrap(
    override val name: InstanceName,
    val config: ValidatorAppBackendConfig,
    val validatorAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: ValidatorAppMetrics,
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
      ValidatorApp,
      ValidatorAppBackendConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      validatorAppParameters,
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
        new ValidatorApp(
          name,
          config,
          validatorAppParameters,
          storage,
          clock,
          loggerFactory,
          tracerProvider,
          retryProvider,
          futureSupervisor,
        )
      )
    )
  }

  override def isActive: Boolean = storage.isActive
}

object ValidatorAppBootstrap {
  val LoggerFactoryKeyName: String = "validator"

  def apply(
      name: String,
      validatorConfig: ValidatorAppBackendConfig,
      validatorAppParameters: SharedCoinAppParameters,
      clock: Clock,
      validatorMetrics: ValidatorAppMetrics,
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
  ): Either[String, ValidatorAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new ValidatorAppBootstrap(
          _,
          validatorConfig,
          validatorAppParameters,
          testingConfigInternal,
          clock,
          validatorMetrics,
          new CommunityStorageFactory(validatorConfig.storage),
          loggerFactory,
          writeHealthDumpToFile,
          retryProvider,
          futureSupervisor,
          configuredOpenTelemetry,
        )
      )
      .leftMap(_.toString)
}
