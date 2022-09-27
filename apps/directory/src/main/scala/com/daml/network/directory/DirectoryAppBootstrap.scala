package com.daml.network.directory

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.directory.admin.DirectoryAutomationService
import com.daml.network.directory.admin.grpc.GrpcDirectoryService
import com.daml.network.directory.config.LocalDirectoryAppConfig
import com.daml.network.directory.metrics.DirectoryAppMetrics
import com.daml.network.directory.store.DirectoryAppStore
import com.daml.network.directory.v0.DirectoryServiceGrpc
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._
import com.daml.network.codegen.CN.{Directory => directoryCodegen}

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
    val store = DirectoryAppStore(storage, loggerFactory)

    val ledgerClient =
      createLedgerClient(
        config.remoteParticipant,
        directoryAppParameters.processingTimeouts,
      )

    val automation = new DirectoryAutomationService(
      config.damlUser,
      store,
      ledgerClient,
      loggerFactory,
      timeouts,
    )

    val scanConnection: ScanConnection =
      new ScanConnection(
        config.remoteScan.clientAdminApi,
        directoryAppParameters.processingTimeouts,
        loggerFactory,
      )

    adminServerRegistry.addService(
      DirectoryServiceGrpc.bindService(
        new GrpcDirectoryService(
          store,
          ledgerClient,
          scanConnection,
          loggerFactory,
        ),
        executionContext,
      )
    )

    val connection = ledgerClient.connection("DirectoryAppBootstrap")

    val directoryApp = for {
      () <- connection.uploadDarFile(new UploadablePackage {
        override def packageId: String =
          ApiTypes.TemplateId.unwrap(directoryCodegen.DirectoryEntry.id).packageId

        override def resourcePath: String = "dar/directory-service-0.1.0.dar"
      }) // TODO(i876) move away from dar upload during init
      providerPartyId <- connection.getOrAllocateParty(config.damlUser)
      () <- store.setProviderParty(providerPartyId)
    } yield {
      new DirectoryApp(
        config,
        directoryAppParameters,
        automation,
        storage,
        store,
        ledgerClient,
        scanConnection,
        clock,
        loggerFactory,
      )
    }

    // TODO(i447): more robust retry + finding out where exceptions (e.g. io.grpc.StatusRuntimeException) disappear too
    EitherT(
      directoryApp
        .recover { err =>
          logger.error(s"Directory initialization failed with $err")
          sys.exit(1)
        }
        .map(Right(_)): Future[Either[String, DirectoryApp]]
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
