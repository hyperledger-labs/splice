package com.daml.network.splitwise

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.{CoinNodeBootstrapBase, CoinRetries}
import com.daml.network.splitwise.config.LocalSplitwiseAppConfig
import com.daml.network.splitwise.metrics.SplitwiseAppMetrics
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.*
import com.digitalasset.canton.time.*

import java.util.concurrent.ScheduledExecutorService
import scala.annotation.nowarn
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Splitwise apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SplitwiseAppBootstrap(
    override val name: InstanceName,
    val config: LocalSplitwiseAppConfig,
    val splitwiseAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: SplitwiseAppMetrics,
    storageFactory: StorageFactory,
    parentLogger: NamedLoggerFactory,
    writeHealthDumpToFile: HealthDumpFunction,
    retryProvider: CoinRetries,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    @nowarn("cat=unused")
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      SplitwiseApp,
      LocalSplitwiseAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      splitwiseAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(SplitwiseAppBootstrap.LoggerFactoryKeyName, name.unwrap),
      writeHealthDumpToFile,
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.fromEither(
      Right(
        new SplitwiseApp(
          name,
          config,
          splitwiseAppParameters,
          storage,
          clock,
          loggerFactory,
          tracerProvider,
          adminServerRegistry,
          retryProvider,
        )
      )
    )
  }

  override def isActive: Boolean = storage.isActive
}

object SplitwiseAppBootstrap {
  val LoggerFactoryKeyName: String = "splitwise"

  def apply(
      name: String,
      splitwiseConfig: LocalSplitwiseAppConfig,
      coinAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      splitwiseMetrics: SplitwiseAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      writeHealthDumpToFile: HealthDumpFunction,
      retryProvider: CoinRetries,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, SplitwiseAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new SplitwiseAppBootstrap(
          _,
          splitwiseConfig,
          coinAppParameters,
          testingConfigInternal,
          clock,
          splitwiseMetrics,
          new CommunityStorageFactory(splitwiseConfig.storage),
          loggerFactory,
          writeHealthDumpToFile,
          retryProvider,
        )
      )
      .leftMap(_.toString)
}
