package com.daml.network.scan

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.*
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.TraceContextDirectives.newTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.codegen.java.cc.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.{CNLedgerClient, CNLedgerConnection, CNNode, CNNodeStatus}
import com.daml.network.http.v0.commonAdmin.CommonAdminResource
import com.daml.network.http.v0.scan.ScanResource
import com.daml.network.scan.admin.http.HttpScanHandler
import com.daml.network.scan.automation.ScanAutomationService
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.ScanStore
import com.daml.network.store.PageLimit
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
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Scan app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class ScanApp(
    override val name: InstanceName,
    val config: ScanAppBackendConfig,
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
    tracer: Tracer,
) extends CNNode[ScanApp.State](
      config.svUser,
      config.participantClient,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  override def initialize(
      ledgerClient: CNLedgerClient,
      // we don't care this party for now as we will get it from either
      // primary party of svc User
      // or readAs party from sv User
      serviceUserPrimaryParty: PartyId,
  ): Future[ScanApp.State] = {
    for {
      svcParty <- getSvcParty(ledgerClient.connection(this.getClass.getSimpleName, loggerFactory))
      store <- Future.successful(
        ScanStore(
          svcParty,
          storage,
          config,
          loggerFactory,
          futureSupervisor,
          // ScanStore needs its own connection for enriching the tx history on-demand
          ledgerClient.connection(this.getClass.getSimpleName, loggerFactory),
          retryProvider,
        )
      )
      automation = new ScanAutomationService(
        config.automation,
        clock,
        ledgerClient,
        retryProvider,
        loggerFactory,
        timeouts,
        store,
      )
      domainId <- waitForDomainConnection(store.domains, config.domains.global.alias)
      _ <- retryProvider.retryForAutomation(
        "wait for open mining round",
        store.multiDomainAcsStore
          .listContracts(roundCodegen.OpenMiningRound.COMPANION, limit = PageLimit(1))
          .map { cs =>
            if (cs.isEmpty) {
              throw Status.FAILED_PRECONDITION
                .withDescription("No active open mining round")
                .asRuntimeException()
            }
          },
        logger,
      )
      _ <- waitForAcsIngestion(store.multiDomainAcsStore, domainId)

      handler = new HttpScanHandler(
        store,
        config,
        clock,
        loggerFactory,
      )

      // TODO(#3467) -- attach handler before app initialization, i.e. in bootstrap
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
                ScanResource.routes(handler),
                CommonAdminResource.routes(adminHandler),
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
      ScanApp.State(
        storage,
        store,
        automation,
        binding,
        loggerFactory.getTracedLogger(ScanApp.State.getClass),
        timeouts,
      )
    }
  }
  override lazy val ports = Map("admin" -> config.adminApi.port)

  override lazy val requiredTemplates = Set(coinCodegen.Coin.TEMPLATE_ID)

  private def getSvcParty(connection: CNLedgerConnection) = retryProvider.retryForAutomation(
    "Querying svcParty from readAs party of sv user",
    getReadAsParty(connection, config.svUser),
    logger,
  )

  private def getReadAsParty(connection: CNLedgerConnection, user: String) =
    connection
      .getUserReadAs(user)
      .map(_.toList match {
        case List() =>
          throw Status.FAILED_PRECONDITION
            .withDescription(s"No readAs party found for user $user")
            .asRuntimeException()
        case List(party) =>
          party
        case _ =>
          throw new IllegalArgumentException(
            "more than 1 readAs party from svUser is not expected"
          )
      })
}

object ScanApp {

  case class State(
      storage: Storage,
      store: ScanStore,
      automation: ScanAutomationService,
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
        storage,
        store,
        automation,
      )(logger)
  }
}
