package com.daml.network.scan

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrap.HealthDumpFunction
import com.daml.network.environment.{CoinNodeBootstrapBase, CoinRetries}
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.scan.metrics.ScanAppMetrics
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

/** Class used to orchester the starting/initialization of Scan apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ScanAppBootstrap(
    override val name: InstanceName,
    val config: LocalScanAppConfig,
    val scanAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: ScanAppMetrics,
    storageFactory: StorageFactory,
    loggerFactory: NamedLoggerFactory,
    writeHealthDumpToFile: HealthDumpFunction,
    retryProvider: CoinRetries,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      ScanApp,
      LocalScanAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      scanAppParameters,
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
        new ScanApp(
          name,
          config,
          scanAppParameters,
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

object ScanAppBootstrap {
  val LoggerFactoryKeyName: String = "scan"

  def apply(
      name: String,
      scanConfig: LocalScanAppConfig,
      coinAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      scanMetrics: ScanAppMetrics,
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
          writeHealthDumpToFile,
          retryProvider,
        )
      )
      .leftMap(_.toString)
}
