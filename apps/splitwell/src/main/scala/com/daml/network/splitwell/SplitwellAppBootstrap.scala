package com.daml.network.splitwell

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.{CoinNodeBootstrapBase, CoinRetries}
import com.daml.network.splitwell.config.SplitwellAppBackendConfig
import com.daml.network.splitwell.metrics.SplitwellAppMetrics
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
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Splitwell apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SplitwellAppBootstrap(
    override val name: InstanceName,
    val config: SplitwellAppBackendConfig,
    val splitwellAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: SplitwellAppMetrics,
    storageFactory: StorageFactory,
    loggerFactory: NamedLoggerFactory,
    writeHealthDumpToFile: HealthDumpFunction,
    retryProvider: CoinRetries,
    futureSupervisor: FutureSupervisor,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      SplitwellApp,
      SplitwellAppBackendConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      splitwellAppParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      writeHealthDumpToFile,
      metrics.grpcMetrics,
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
          retryProvider,
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
      coinAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      splitwellMetrics: SplitwellAppMetrics,
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
          retryProvider,
          futureSupervisor,
        )
      )
      .leftMap(_.toString)
}
