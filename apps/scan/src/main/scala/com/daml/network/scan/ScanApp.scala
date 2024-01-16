package com.daml.network.scan

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.TraceContextDirectives.withTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.codegen.java.cc.round as roundCodegen
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.{
  CNLedgerClient,
  CNNode,
  CNNodeStatus,
  DarResources,
  ParticipantAdminConnection,
  RetryFor,
  SequencerAdminConnection,
}
import com.daml.network.http.v0.external.common_admin.CommonAdminResource
import com.daml.network.http.v0.external.scan.ScanResource as ExternalScanResource
import com.daml.network.http.v0.scan.ScanResource as InternalScanResource
import com.daml.network.scan.admin.http.{HttpScanHandler, HttpExternalScanHandler}
import com.daml.network.scan.automation.ScanAutomationService
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.metrics.ScanAppMetrics
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
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.cors
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings

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
    nodeMetrics: ScanAppMetrics,
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
      futureSupervisor,
      nodeMetrics,
    ) {

  override def packages =
    super.packages ++ DarResources.cantonNameService.all ++ DarResources.svcGovernance.all

  override def initialize(
      ledgerClient: CNLedgerClient,
      // we don't care about the primary party in scan as that points to the SV party while we need the SVC party
      // which we read below.
      // primary party of svc User
      // or readAs party from sv User
      serviceUserPrimaryParty: PartyId,
  ): Future[ScanApp.State] = {
    for {
      svcParty <- appInitStep("Get SVC party from user metadata") {
        ledgerClient
          .readOnlyConnection(
            this.getClass.getSimpleName,
            loggerFactory,
          )
          .getSvcPartyFromUserMetadata(config.svUser)
      }
      store = ScanStore(
        serviceUserPrimaryParty = serviceUserPrimaryParty,
        svcParty = svcParty,
        storage,
        loggerFactory,
        retryProvider,
      )
      participantAdminConnection = new ParticipantAdminConnection(
        config.participantClient.adminApi,
        loggerFactory,
        retryProvider,
        clock,
      )
      sequencerAdminConnection = new SequencerAdminConnection(
        config.sequencerAdminClient,
        loggerFactory,
        retryProvider,
        clock,
      )
      automation = new ScanAutomationService(
        config.automation,
        clock,
        ledgerClient,
        retryProvider,
        loggerFactory,
        store,
        config.ingestFromParticipantBegin,
      )
      _ <- appInitStep("Wait until there is an OpenMiningRound contract") {
        retryProvider.waitUntil(
          RetryFor.WaitingOnInitDependency,
          "there is an OpenMiningRound contract",
          store.multiDomainAcsStore
            .listContracts(roundCodegen.OpenMiningRound.COMPANION, limit = PageLimit.tryCreate(1))
            .map { cs =>
              if (cs.isEmpty) {
                throw Status.NOT_FOUND
                  .withDescription("OpenMiningRound contract")
                  .asRuntimeException()
              }
            },
          logger,
        )
      }

      internalHandler = new HttpScanHandler(
        participantAdminConnection,
        store,
        config.miningRoundsCacheTimeToLiveOverride,
        loggerFactory,
      )

      externalHandler = new HttpExternalScanHandler(
        store,
        sequencerAdminConnection,
        loggerFactory,
      )

      adminHandler = new HttpAdminHandler(
        status
          .map(CNNodeStatus.fromNodeStatus)
          .map(NodeStatus.Success(_)),
        loggerFactory,
      )

      routes = cors(
        CorsSettings(ac).withExposedHeaders(Seq("traceparent"))
      ) {
        withTraceContext { traceContext =>
          {

            requestLogger(traceContext) {
              HttpErrorHandler(loggerFactory)(traceContext) {
                concat(
                  InternalScanResource.routes(internalHandler, _ => provide(traceContext)),
                  ExternalScanResource.routes(externalHandler, _ => provide(traceContext)),
                  pathPrefix("api" / "scan")(
                    CommonAdminResource.routes(adminHandler, _ => provide(traceContext))
                  ),
                )
              }
            }
          }
        }
      }

      binding <- appInitStep(s"Start http server on ${config.adminApi.clientConfig}") {
        Http()
          .newServerAt(
            config.adminApi.clientConfig.address,
            config.adminApi.clientConfig.port.unwrap,
          )
          .bind(
            routes
          )
      }
    } yield {
      ScanApp.State(
        participantAdminConnection,
        sequencerAdminConnection,
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

  override lazy val requiredPackageIds = Set(DarResources.cantonCoin.bootstrap.packageId)
}

object ScanApp {

  case class State(
      participantAdminConnection: ParticipantAdminConnection,
      sequencerAdminConnection: SequencerAdminConnection,
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
          timeouts.shutdownNetwork,
        ),
        automation,
        store,
        storage,
        sequencerAdminConnection,
        participantAdminConnection,
      )(logger)
  }
}
