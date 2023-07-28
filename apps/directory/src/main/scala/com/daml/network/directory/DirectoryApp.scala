package com.daml.network.directory

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.*
import akka.stream.Materializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.TraceContextDirectives.newTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.directory.admin.http.HttpDirectoryHandler
import com.daml.network.directory.automation.DirectoryAutomationService
import com.daml.network.directory.config.DirectoryAppBackendConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CNLedgerClient, CNNode, CNNodeStatus}
import com.daml.network.http.v0.external.commonAdmin.CommonAdminResource
import com.daml.network.http.v0.directory.DirectoryResource
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
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
    val config: DirectoryAppBackendConfig,
    val coinAppParameters: SharedCNNodeAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    mat: Materializer,
    tracer: Tracer,
) extends CNNode[DirectoryApp.State](
      config.svUser,
      config.participantClient,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
    ) {

  override def initialize(
      ledgerClient: CNLedgerClient,
      svParty: PartyId,
  ): Future[DirectoryApp.State] =
    for {
      initConnection <- Future.successful(
        ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
      )
      svcParty <- initConnection.getSvcPartyFromUserMetadata(config.svUser)
      scanConnection <- ScanConnection(
        ledgerClient,
        config.scanClient,
        clock,
        retryProvider,
        loggerFactory,
      )
      store = DirectoryStore(
        providerParty = svcParty,
        svcParty = svcParty,
        storage,
        config.domains,
        loggerFactory,
        retryProvider,
      )
      automation = new DirectoryAutomationService(
        config.automation,
        clock,
        store,
        ledgerClient,
        scanConnection,
        retryProvider,
        loggerFactory,
      )
      _ <- store.domains.waitForDomainConnection(config.domains.global.alias)
      handler = new HttpDirectoryHandler(
        store,
        loggerFactory,
      )

      adminHandler = new HttpAdminHandler(
        status
          .map(CNNodeStatus.fromNodeStatus)
          .map(NodeStatus.Success(_)),
        loggerFactory,
      )

      routes = cors() {
        newTraceContext { traceContext =>
          requestLogger(traceContext) {
            HttpErrorHandler(loggerFactory)(traceContext) {
              concat(
                DirectoryResource.routes(handler, _ => provide(())),
                CommonAdminResource.routes(adminHandler, _ => provide(())),
              )
            }
          }
        }
      }
      _ = logger.info(s"Starting http server on ${config.adminApi.clientConfig}")
      binding <- Http()
        .newServerAt(
          config.adminApi.clientConfig.address,
          config.adminApi.clientConfig.port.unwrap,
        )
        .bind(
          routes
        )
    } yield {
      new DirectoryApp.State(
        automation,
        storage,
        store,
        scanConnection,
        binding,
        loggerFactory.getTracedLogger(DirectoryApp.State.getClass),
        timeouts,
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
      binding: Http.ServerBinding,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  )(implicit el: ErrorLoggingContext)
      extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      Lifecycle.close(
        AsyncCloseable(
          "http binding",
          binding.terminate(timeouts.shutdownNetwork.asFiniteApproximation),
          timeouts.shutdownNetwork.unwrap,
        ),
        automation,
        storage,
        store,
        scanConnection,
      )(logger)

  }
}
