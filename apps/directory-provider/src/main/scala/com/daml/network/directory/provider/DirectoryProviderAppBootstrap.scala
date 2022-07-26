package com.daml.network.directory.provider

import java.util.concurrent.ScheduledExecutorService

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.examples.v0.DirectoryProviderServiceGrpc
import com.daml.network.directory.provider.admin.grpc.GrpcDirectoryProviderService
import com.daml.network.directory.provider.config.LocalDirectoryProviderAppConfig
import com.daml.network.directory.provider.metrics.DirectoryProviderAppMetrics
import com.daml.network.directory.provider.store.DirectoryProviderAppStore
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

/** Class used to orchester the starting/initialization of DirectoryProvider apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class DirectoryProviderAppBootstrap(
    override val name: InstanceName,
    val config: LocalDirectoryProviderAppConfig,
    val directoryProviderAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: DirectoryProviderAppMetrics,
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
      DirectoryProviderApp,
      LocalDirectoryProviderAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      directoryProviderAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(DirectoryProviderAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.rightT[Future, String] {
      val dummyStore = DirectoryProviderAppStore(storage, loggerFactory)

      val connection =
        createLedgerConnection(
          config.remoteParticipant,
          directoryProviderAppParameters.processingTimeouts,
        )

      adminServerRegistry.addService(
        DirectoryProviderServiceGrpc.bindService(
          new GrpcDirectoryProviderService(connection, config.damlUser, loggerFactory),
          executionContext,
        )
      )
      new DirectoryProviderApp(
        config,
        directoryProviderAppParameters,
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
object DirectoryProviderAppBootstrap {
  val LoggerFactoryKeyName: String = "directoryProvider"

  trait Factory {
    def create(
        name: String,
        directoryProviderConfig: LocalDirectoryProviderAppConfig,
        coinAppParameters: SharedCoinAppParameters,
        clock: Clock,
        testingTimeService: TestingTimeService,
        directoryProviderMetrics: DirectoryProviderAppMetrics,
        testingConfig: TestingConfigInternal,
        futureSupervisor: FutureSupervisor,
        loggerFactory: NamedLoggerFactory,
    )(implicit
        executionContext: ExecutionContextIdlenessExecutorService,
        scheduler: ScheduledExecutorService,
        actorSystem: ActorSystem,
        executionSequencerFactory: ExecutionSequencerFactory,
    ): Either[String, DirectoryProviderAppBootstrap]
  }

  object DirectoryProviderFactory extends Factory {

    override def create(
        name: String,
        directoryProviderConfig: LocalDirectoryProviderAppConfig,
        coinAppParameters: SharedCoinAppParameters,
        clock: Clock,
        testingTimeService: TestingTimeService,
        directoryProviderMetrics: DirectoryProviderAppMetrics,
        testingConfigInternal: TestingConfigInternal,
        futureSupervisor: FutureSupervisor,
        loggerFactory: NamedLoggerFactory,
    )(implicit
        executionContext: ExecutionContextIdlenessExecutorService,
        scheduler: ScheduledExecutorService,
        actorSystem: ActorSystem,
        executionSequencerFactory: ExecutionSequencerFactory,
    ): Either[String, DirectoryProviderAppBootstrap] =
      InstanceName
        .create(name)
        .map(
          new DirectoryProviderAppBootstrap(
            _,
            directoryProviderConfig,
            coinAppParameters,
            testingConfigInternal,
            clock,
            directoryProviderMetrics,
            new CommunityStorageFactory(directoryProviderConfig.storage),
            loggerFactory,
          )
        )
        .leftMap(_.toString)
  }
}
