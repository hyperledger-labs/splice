package com.daml.network.directory

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods
import akka.stream.Materializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import ch.megard.akka.http.cors.scaladsl.model.{HttpHeaderRange, HttpOriginMatcher}
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.admin.api.TraceContextDirectives.newTraceContext
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.directory.admin.http.HttpDirectoryHandler
import com.daml.network.directory.automation.DirectoryAutomationService
import com.daml.network.directory.config.LocalDirectoryAppConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.{CoinLedgerClient, CoinNode}
import com.daml.network.http.v0.directory.DirectoryResource
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
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
    futureSupervisor: FutureSupervisor,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    mat: Materializer,
    tracer: Tracer,
) extends CoinNode[DirectoryApp.State](
      config.ledgerApiUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      providerPartyId: PartyId,
  ): Future[DirectoryApp.State] =
    for {
      scanConnection <- Future.successful(
        new ScanConnection(
          ledgerClient,
          config.remoteScan,
          clock,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )
      )
      svcParty <- retryProvider.retryForAutomation(
        "getSvcPartyId",
        scanConnection.getSvcPartyId(),
        logger,
      )
      store = DirectoryStore(
        providerParty = providerPartyId,
        svcParty = svcParty,
        storage,
        config.domains,
        loggerFactory,
        futureSupervisor,
        retryProvider,
      )
      automation = new DirectoryAutomationService(
        config.automation,
        clock,
        store,
        ledgerClient,
        scanConnection,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      _ <- waitForDomainConnection(store.domains, config.domains.global)
      // TODO(#2024) Validate that this is secure.
      settings = CorsSettings.defaultSettings
        .withAllowGenericHttpRequests(true)
        .withAllowedOrigins(HttpOriginMatcher.`*`)
        .withAllowedMethods(
          Seq(
            HttpMethods.GET,
            HttpMethods.PUT,
            HttpMethods.DELETE,
            HttpMethods.POST,
            HttpMethods.OPTIONS,
          )
        )
        .withAllowedHeaders(HttpHeaderRange.`*`)
      handler = new HttpDirectoryHandler(
        store,
        loggerFactory,
      )
      routes = cors() {
        newTraceContext { traceContext =>
          requestLogger(traceContext) {
            DirectoryResource.routes(
              handler
            )
          }
        }
      }
      httpConfig = config.adminApi.clientConfig.copy(
        // TODO(#2019) Remove once we disabled gRPC Servers completely.
        port = config.adminApi.port + 1000
      )
      _ = logger.info(s"Starting http server on ${httpConfig}")
      binding <- Http()
        .newServerAt(
          httpConfig.address,
          httpConfig.port.unwrap,
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
