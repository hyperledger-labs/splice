package com.daml.network.directory

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.directory.admin.grpc.GrpcDirectoryService
import com.daml.network.directory.automation.DirectoryAutomationService
import com.daml.network.directory.config.LocalDirectoryAppConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.directory.v0.DirectoryServiceGrpc
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNode}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.api.trace.Tracer
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Directory app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class DirectoryApp(
    override val name: InstanceName,
    val config: LocalDirectoryAppConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    adminServerRegistry: CantonMutableHandlerRegistry,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    mat: Materializer,
    tracer: Tracer,
) extends CoinNode[DirectoryApp.State](coinAppParameters, loggerFactory, tracerProvider) {

  override def initialize(): Future[DirectoryApp.State] =
    for {
      ledgerClient <-
        Future {
          createLedgerClient(
            config.remoteParticipant
          )
        }

      scanConnection =
        new ScanConnection(
          config.remoteScan.clientAdminApi,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )

      connection = ledgerClient.connection("DirectoryAppBootstrap")

      providerPartyId <- connection.retryLedgerApi(
        connection.getPrimaryParty(config.damlUser),
        CoinLedgerConnection.RetryOnUserManagementError,
      )
      _ = logger.info(s"Got primary party of Directory user: $providerPartyId")
      svcParty <- scanConnection.getSvcPartyId()
      store = DirectoryStore(
        providerParty = providerPartyId,
        svcParty = svcParty,
        storage,
        loggerFactory,
      )
      automation = new DirectoryAutomationService(
        store,
        ledgerClient,
        loggerFactory,
        timeouts,
      )
      grpcServer =
        new GrpcDirectoryService(
          store,
          loggerFactory,
        )
    } yield {
      adminServerRegistry.addService(DirectoryServiceGrpc.bindService(grpcServer, ec))
      new DirectoryApp.State(
        automation,
        storage,
        store,
        ledgerClient,
        scanConnection,
        loggerFactory.getTracedLogger(DirectoryApp.State.getClass),
      )
    }

  override val ports =
    Map("admin" -> config.adminApi.port)
}

object DirectoryApp {
  case class State(
      automation: DirectoryAutomationService,
      storage: Storage,
      store: DirectoryStore,
      ledgerClient: CoinLedgerClient,
      scanConnection: ScanConnection,
      logger: TracedLogger,
  ) extends AutoCloseable {
    override def close() =
      Lifecycle.close(
        automation,
        storage,
        store,
        ledgerClient,
        scanConnection,
      )(logger)

  }
}
