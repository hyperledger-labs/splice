package com.daml.network.directory

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.directory.admin.grpc.GrpcDirectoryService
import com.daml.network.directory.automation.DirectoryAutomationService
import com.daml.network.directory.config.LocalDirectoryAppConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.directory.v0.DirectoryServiceGrpc
import com.daml.network.environment.{CoinLedgerClient, CoinNode, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.HasHealth
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
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
    retryProvider: CoinRetries,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    mat: Materializer,
    tracer: Tracer,
) extends CoinNode[DirectoryApp.State](
      config.damlUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      retryProvider,
    ) {

  override def initialize(
      ledgerClient: CoinLedgerClient,
      providerPartyId: PartyId,
  ): Future[DirectoryApp.State] =
    for {
      scanConnection <- Future.successful(
        new ScanConnection(
          config.remoteScan.clientAdminApi,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )
      )
      svcParty <- retryProvider.retryForAutomation(
        "getSvcPartyId",
        scanConnection.getSvcPartyId(),
        this,
      )
      store = DirectoryStore(
        providerParty = providerPartyId,
        svcParty = svcParty,
        storage,
        loggerFactory,
      )
      automation = new DirectoryAutomationService(
        config.automation,
        clock,
        store,
        ledgerClient,
        scanConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      grpcServer =
        new GrpcDirectoryService(
          store,
          loggerFactory,
        )
    } yield {
      adminServerRegistry.addService(DirectoryServiceGrpc.bindService(grpcServer, ec)).discard
      new DirectoryApp.State(
        automation,
        storage,
        store,
        scanConnection,
        loggerFactory.getTracedLogger(DirectoryApp.State.getClass),
      )
    }

  override lazy val ports =
    Map("admin" -> config.adminApi.port)

  override lazy val requiredTemplates = Set(directoryCodegen.DirectoryInstall.TEMPLATE_ID)
}

object DirectoryApp {
  case class State(
      automation: DirectoryAutomationService,
      storage: Storage,
      store: DirectoryStore,
      scanConnection: ScanConnection,
      logger: TracedLogger,
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      Lifecycle.close(
        automation,
        storage,
        store,
        scanConnection,
      )(logger)

  }
}
