package com.daml.network.validator

import java.util.concurrent.ScheduledExecutorService

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.examples.v0.DummyServiceGrpc
import com.daml.network.validator.admin.grpc.GrpcDummyService
import com.daml.network.validator.config.{LocalValidatorConfig, ValidatorNodeParameters}
import com.daml.network.validator.metrics.ValidatorMetrics
import com.daml.network.validator.store.DummyStore
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._

import scala.annotation.nowarn
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Validator node.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ValidatorNodeBootstrap(
    override val name: InstanceName,
    val config: LocalValidatorConfig,
    val validatorNodeParameters: ValidatorNodeParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: ValidatorMetrics,
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
      ValidatorNode,
      LocalValidatorConfig,
      ValidatorNodeParameters,
    ](
      name,
      config,
      validatorNodeParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(ValidatorNodeBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.rightT[Future, String] {
      val dummyStore = DummyStore(storage, loggerFactory)

      adminServerRegistry.addService(
        DummyServiceGrpc.bindService(new GrpcDummyService(loggerFactory), executionContext)
      )
      new ValidatorNode(config, validatorNodeParameters, storage, dummyStore, clock, loggerFactory)
    }
  }

  override def isActive: Boolean = storage.isActive
}

// TODO(Arne): Do we need this factory construction?
// I think Canton only needs this for being able to generalize a community/enterprise participant with the same method
// while being able to return an `Either` to signal that the initialization failed
object ValidatorNodeBootstrap {
  val LoggerFactoryKeyName: String = "validator"

  trait Factory[PC <: LocalValidatorConfig] {
    def create(
        name: String,
        validatorConfig: LocalValidatorConfig,
        validatorNodeParameters: ValidatorNodeParameters,
        clock: Clock,
        testingTimeService: TestingTimeService,
        validatorMetrics: ValidatorMetrics,
        testingConfig: TestingConfigInternal,
        futureSupervisor: FutureSupervisor,
        loggerFactory: NamedLoggerFactory,
    )(implicit
        executionContext: ExecutionContextIdlenessExecutorService,
        scheduler: ScheduledExecutorService,
        actorSystem: ActorSystem,
        executionSequencerFactory: ExecutionSequencerFactory,
    ): Either[String, ValidatorNodeBootstrap]
  }

  object ValidatorFactory extends Factory[LocalValidatorConfig] {

    override def create(
        name: String,
        validatorConfig: LocalValidatorConfig,
        validatorNodeParameters: ValidatorNodeParameters,
        clock: Clock,
        testingTimeService: TestingTimeService,
        validatorMetrics: ValidatorMetrics,
        testingConfigInternal: TestingConfigInternal,
        futureSupervisor: FutureSupervisor,
        loggerFactory: NamedLoggerFactory,
    )(implicit
        executionContext: ExecutionContextIdlenessExecutorService,
        scheduler: ScheduledExecutorService,
        actorSystem: ActorSystem,
        executionSequencerFactory: ExecutionSequencerFactory,
    ): Either[String, ValidatorNodeBootstrap] =
      InstanceName
        .create(name)
        .map(
          new ValidatorNodeBootstrap(
            _,
            validatorConfig,
            validatorNodeParameters,
            testingConfigInternal,
            clock,
            validatorMetrics,
            new CommunityStorageFactory(validatorConfig.storage),
            loggerFactory,
          )
        )
        .leftMap(_.toString)
  }
}
