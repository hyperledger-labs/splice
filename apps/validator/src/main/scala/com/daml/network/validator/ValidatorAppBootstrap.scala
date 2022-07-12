package com.daml.network.validator

import java.util.concurrent.ScheduledExecutorService

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.examples.v0.{ValidatorAppServiceGrpc, WalletServiceGrpc}
import com.daml.network.validator.admin.grpc.{GrpcValidatorAppService, GrpcWalletService}
import com.daml.network.validator.config.{LocalValidatorAppConfig, ValidatorAppParameters}
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.daml.network.validator.store.ValidatorAppStore
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.ledger.api.client.LedgerConnection
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._

import scala.annotation.nowarn
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Validator node.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ValidatorAppBootstrap(
    override val name: InstanceName,
    val config: LocalValidatorAppConfig,
    val validatorNodeParameters: ValidatorAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: ValidatorAppMetrics,
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
      ValidatorAppNode,
      LocalValidatorAppConfig,
      ValidatorAppParameters,
    ](
      name,
      config,
      validatorNodeParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(ValidatorAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.rightT[Future, String] {
      val dummyStore = ValidatorAppStore(storage, loggerFactory)

      import com.daml.ledger.api.refinements.{ApiTypes => A}
      // configuration mostly copied from Canton
      val connection: LedgerConnection = LedgerConnection(
        config.remoteParticipant.ledgerApi,
        A.ApplicationId("applicationId"),
        10,
        A.Party("ThisPartyIsCurrentlyNotUsed"),
        A.WorkflowId("workflowId"),
        CommandClientConfiguration.default.copy(
          maxCommandsInFlight = 0, // set this to a silly value, to enforce it is never used
          maxParallelSubmissions =
            1000000, // We need a high value to work around https://github.com/digital-asset/daml/issues/8017
          // This defines the maximum timeout that can be specified on admin workflow services such as the ping command
          // The parameter name is misleading; it does not affect the deduplication period for the commands.
          defaultDeduplicationTime = java.time.Duration.ofMinutes(1),
        ),
        config.remoteParticipant.token,
        validatorNodeParameters.processingTimeouts,
        loggerFactory,
        tracerProvider,
        _ => true,
      )

      adminServerRegistry.addService(
        ValidatorAppServiceGrpc.bindService(
          new GrpcValidatorAppService(loggerFactory),
          executionContext,
        )
      )
      adminServerRegistry.addService(
        WalletServiceGrpc.bindService(
          new GrpcWalletService(connection, loggerFactory),
          executionContext,
        )
      )
      new ValidatorAppNode(
        config,
        validatorNodeParameters,
        storage,
        dummyStore,
        clock,
        loggerFactory,
      )
    }
  }

  override def isActive: Boolean = storage.isActive
}

// TODO(Arne): Do we need this factory construction?
// I think Canton only needs this for being able to generalize a community/enterprise participant with the same method
// while being able to return an `Either` to signal that the initialization failed
object ValidatorAppBootstrap {
  val LoggerFactoryKeyName: String = "validator"

  trait Factory {
    def create(
        name: String,
        validatorConfig: LocalValidatorAppConfig,
        validatorNodeParameters: ValidatorAppParameters,
        clock: Clock,
        testingTimeService: TestingTimeService,
        validatorMetrics: ValidatorAppMetrics,
        testingConfig: TestingConfigInternal,
        futureSupervisor: FutureSupervisor,
        loggerFactory: NamedLoggerFactory,
    )(implicit
        executionContext: ExecutionContextIdlenessExecutorService,
        scheduler: ScheduledExecutorService,
        actorSystem: ActorSystem,
        executionSequencerFactory: ExecutionSequencerFactory,
    ): Either[String, ValidatorAppBootstrap]
  }

  object ValidatorFactory extends Factory {

    override def create(
        name: String,
        validatorConfig: LocalValidatorAppConfig,
        validatorNodeParameters: ValidatorAppParameters,
        clock: Clock,
        testingTimeService: TestingTimeService,
        validatorMetrics: ValidatorAppMetrics,
        testingConfigInternal: TestingConfigInternal,
        futureSupervisor: FutureSupervisor,
        loggerFactory: NamedLoggerFactory,
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
