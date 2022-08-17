package com.daml.network.validator

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerConnection, CoinNodeBootstrapBase}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.validator.admin.grpc.GrpcValidatorAppService
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.daml.network.validator.store.ValidatorAppStore
import com.daml.network.validator.v0.ValidatorAppServiceGrpc
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
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    @nowarn("cat=unused")
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      ValidatorAppNode,
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
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.rightT[Future, String] {
      val dummyStore = ValidatorAppStore(storage, loggerFactory)

      val connection: CoinLedgerConnection =
        createLedgerConnection(config.remoteParticipant, validatorAppParameters.processingTimeouts)

      val scanConnection: ScanConnection =
        new ScanConnection(
          config.remoteScan.clientAdminApi,
          validatorAppParameters.processingTimeouts,
          loggerFactory,
        )

      adminServerRegistry.addService(
        ValidatorAppServiceGrpc.bindService(
          new GrpcValidatorAppService(
            connection,
            scanConnection,
            dummyStore,
            config.damlUser,
            loggerFactory,
          ),
          executionContext,
        )
      )
      new ValidatorAppNode(
        config,
        validatorAppParameters,
        storage,
        dummyStore,
        scanConnection,
        clock,
        loggerFactory,
      )
    }
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
        )
      )
      .leftMap(_.toString)
}
