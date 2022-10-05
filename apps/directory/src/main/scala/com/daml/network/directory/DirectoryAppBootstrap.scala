package com.daml.network.directory

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.directory.config.LocalDirectoryAppConfig
import com.daml.network.directory.metrics.DirectoryAppMetrics
import com.daml.network.environment.CoinNodeBootstrapBase
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

/** Class used to orchestrate the starting/initialization of Directory apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class DirectoryAppBootstrap(
    override val name: InstanceName,
    val config: LocalDirectoryAppConfig,
    val directoryAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: DirectoryAppMetrics,
    storageFactory: StorageFactory,
    parentLogger: NamedLoggerFactory,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    @nowarn("cat=unused")
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      DirectoryApp,
      LocalDirectoryAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      directoryAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(DirectoryAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.fromEither(
      Right(
        new DirectoryApp(
          name,
          config,
          directoryAppParameters,
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

object DirectoryAppBootstrap {
  val LoggerFactoryKeyName: String = "directory"

  def apply(
      name: String,
      directoryConfig: LocalDirectoryAppConfig,
      coinAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      directoryMetrics: DirectoryAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, DirectoryAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new DirectoryAppBootstrap(
          _,
          directoryConfig,
          coinAppParameters,
          testingConfigInternal,
          clock,
          directoryMetrics,
          new CommunityStorageFactory(directoryConfig.storage),
          loggerFactory,
        )
      )
      .leftMap(_.toString)
}
