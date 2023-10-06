package com.daml.network.splitwell

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.*
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.TraceContextDirectives.withTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.{
  CNLedgerClient,
  CNLedgerConnection,
  CNNode,
  CNNodeStatus,
  ParticipantAdminConnection,
}
import com.daml.network.http.v0.external.common_admin.CommonAdminResource
import com.daml.network.http.v0.splitwell.SplitwellResource
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwell.admin.api.client.commands.HttpSplitwellAppClient.SplitwellDomains
import com.daml.network.splitwell.admin.http.HttpSplitwellHandler
import com.daml.network.splitwell.automation.SplitwellAutomationService
import com.daml.network.splitwell.config.SplitwellAppBackendConfig
import com.daml.network.splitwell.metrics.SplitwellAppMetrics
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Splitwell app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SplitwellApp(
    override val name: InstanceName,
    val config: SplitwellAppBackendConfig,
    val coinAppParameters: SharedCNNodeAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    metrics: SplitwellAppMetrics,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CNNode[SplitwellApp.State](
      config.providerUser,
      config.participantClient,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      metrics,
    ) {

  override lazy val ports = Map("admin" -> config.adminApi.port)

  override def initialize(
      ledgerClient: CNLedgerClient,
      party: PartyId,
  ): Future[SplitwellApp.State] = for {
    store <- Future.successful(
      SplitwellStore(party, storage, config.domains, loggerFactory, retryProvider)
    )
    scanConnection <-
      ScanConnection(
        ledgerClient,
        config.scanClient,
        clock,
        retryProvider,
        loggerFactory,
      )
    participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      loggerFactory,
      retryProvider,
      clock,
    )
    automation = new SplitwellAutomationService(
      config.automation,
      clock,
      store,
      ledgerClient,
      config.domains.global.alias,
      scanConnection,
      retryProvider,
      loggerFactory,
    )
    _ <- store.domains.waitForDomainConnection(config.domains.global.alias)
    preferred <- store.domains.waitForDomainConnection(config.domains.splitwell.preferred.alias)
    others <- config.domains.splitwell.others
      .map(_.alias)
      .toList
      .traverse(store.domains.waitForDomainConnection(_))

    splitwellDomains = SplitwellDomains(preferred, others)

    _ <- createSplitwellRules(splitwellDomains, automation)

    handler = new HttpSplitwellHandler(
      participantAdminConnection,
      SplitwellDomains(preferred, others),
      party,
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
      withTraceContext { traceContext =>
        requestLogger(traceContext) {
          HttpErrorHandler(loggerFactory)(traceContext) {
            concat(
              SplitwellResource.routes(handler, _ => provide(traceContext)),
              CommonAdminResource.routes(adminHandler, _ => provide(traceContext)),
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
    SplitwellApp.State(
      automation,
      storage,
      store,
      scanConnection,
      binding,
      participantAdminConnection,
      loggerFactory.getTracedLogger(SplitwellApp.State.getClass),
      timeouts,
    )
  }

  private def createSplitwellRules(
      domains: SplitwellDomains,
      automation: SplitwellAutomationService,
  ): Future[Unit] =
    (domains.preferred +: domains.others).toList.traverse_(createSplitwellRules(_, automation))

  private def createSplitwellRules(
      domain: DomainId,
      automation: SplitwellAutomationService,
  ): Future[Unit] =
    automation.store.lookupSplitwellRules(domain).flatMap {
      case QueryResult(offset, None) =>
        automation.connection
          .submit(
            Seq(automation.store.providerParty),
            Seq.empty,
            new splitwellCodegen.SplitwellRules(
              automation.store.providerParty.toProtoPrimitive
            ).create,
          )
          .withDomainId(domain)
          .withDedup(
            CNLedgerConnection.CommandId(
              "com.daml.network.splitwell.createSplitwellRules",
              Seq(automation.store.providerParty),
              domain.toProtoPrimitive,
            ),
            offset,
          )
          .yieldUnit()
      case QueryResult(_, Some(_)) => Future.unit
    }

  override lazy val requiredTemplates = Set(splitwellCodegen.SplitwellInstall.TEMPLATE_ID)
}

object SplitwellApp {
  case class State(
      automation: SplitwellAutomationService,
      storage: Storage,
      store: SplitwellStore,
      scanConnection: ScanConnection,
      binding: Http.ServerBinding,
      participantAdminConnection: ParticipantAdminConnection,
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
        participantAdminConnection,
      )(logger)
  }
}
