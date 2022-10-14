package com.daml.network.validator

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._

import java.util.concurrent.ScheduledExecutorService
import scala.annotation.nowarn
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Validator node.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ValidatorAppBootstrap(
    override val name: InstanceName,
    val config: LocalValidatorAppConfig,
    val validatorAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: ValidatorAppMetrics,
    storageFactory: StorageFactory,
    parentLogger: NamedLoggerFactory,
    writeHealthDumpToFile: HealthDumpFunction,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    @nowarn("cat=unused")
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      ValidatorApp,
      LocalValidatorAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      validatorAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(ValidatorAppBootstrap.LoggerFactoryKeyName, name.unwrap),
      writeHealthDumpToFile,
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
          adminServerRegistry,
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
      validatorConfig: LocalValidatorAppConfig,
      validatorAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      validatorMetrics: ValidatorAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      writeHealthDumpToFile: HealthDumpFunction,
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
        )
      )
      .leftMap(_.toString)
}
