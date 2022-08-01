package com.daml.network.directory.user

import java.util.concurrent.ScheduledExecutorService

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.directory_provider.v0.DirectoryProviderServiceGrpc
import com.daml.network.directory_user.v0.DirectoryUserServiceGrpc
import com.daml.network.directory.user.admin.grpc.GrpcDirectoryUserService
import com.daml.network.directory.user.config.LocalDirectoryUserAppConfig
import com.daml.network.directory.user.metrics.DirectoryUserAppMetrics
import com.daml.network.directory.user.store.DirectoryUserAppStore
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.ledger.api.client.LedgerConnection
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._
import com.digitalasset.canton.tracing.TraceContextGrpc

import scala.annotation.nowarn
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of DirectoryUser apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class DirectoryUserAppBootstrap(
    override val name: InstanceName,
    val config: LocalDirectoryUserAppConfig,
    val directoryUserAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: DirectoryUserAppMetrics,
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
      DirectoryUserApp,
      LocalDirectoryUserAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      directoryUserAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(DirectoryUserAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.rightT[Future, String] {
      val dummyStore = DirectoryUserAppStore(storage, loggerFactory)

      val connection =
        createLedgerConnection(
          config.remoteParticipant,
          directoryUserAppParameters.processingTimeouts,
        )

      val providerChannel = ClientChannelBuilder.createChannel(
        config.remoteDirectoryProvider.clientAdminApi
      )(executionContext)
      val providerStub =
        DirectoryProviderServiceGrpc
          .stub(providerChannel)
          .withInterceptors(TraceContextGrpc.clientInterceptor)

      adminServerRegistry.addService(
        DirectoryUserServiceGrpc.bindService(
          new GrpcDirectoryUserService(connection, providerStub, config.damlUser, loggerFactory),
          executionContext,
        )
      )
      new DirectoryUserApp(
        config,
        directoryUserAppParameters,
        storage,
        dummyStore,
        clock,
        loggerFactory,
      )
    }
  }

  override def isActive: Boolean = storage.isActive
}

object DirectoryUserAppBootstrap {
  val LoggerFactoryKeyName: String = "directoryUser"

  def apply(
      name: String,
      directoryUserConfig: LocalDirectoryUserAppConfig,
      coinAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      directoryUserMetrics: DirectoryUserAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, DirectoryUserAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new DirectoryUserAppBootstrap(
          _,
          directoryUserConfig,
          coinAppParameters,
          testingConfigInternal,
          clock,
          directoryUserMetrics,
          new CommunityStorageFactory(directoryUserConfig.storage),
          loggerFactory,
        )
      )
      .leftMap(_.toString)
}
