package com.daml.network.scan

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.TraceContextDirectives.withTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.codegen.java.splice.round as roundCodegen
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
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.scan.admin.http.{HttpExternalScanHandler, HttpScanHandler}
import com.daml.network.scan.automation.ScanAutomationService
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.metrics.ScanAppMetrics
import com.daml.network.scan.store.ScanStore
import com.daml.network.scan.store.db.{ScanAggregatesReader, ScanAggregatesReaderContext}
import com.daml.network.scan.dso.DsoAnsResolver
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
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.cors
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings

import scala.concurrent.{ExecutionContextExecutor, Future}
import org.apache.pekko.stream.Materializer

/** Class representing a Scan app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class ScanApp(
    override val name: InstanceName,
    val config: ScanAppBackendConfig,
    val amuletAppParameters: SharedCNNodeAppParameters,
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
      amuletAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      nodeMetrics,
    ) {

  override def packages =
    super.packages ++ DarResources.amuletNameService.all ++ DarResources.dsoGovernance.all

  override def initialize(
      ledgerClient: CNLedgerClient,
      // We don't care about the primary party in scan as that points to the SV party while we need the DSO party
      // which we read below.
      serviceUserPrimaryParty: PartyId,
  )(implicit tc: TraceContext): Future[ScanApp.State] = {
    val appInitConnection = ledgerClient
      .readOnlyConnection(
        this.getClass.getSimpleName,
        loggerFactory,
      )
    for {
      dsoParty <- appInitStep("Get DSO party from user metadata") {
        appInitConnection.getDsoPartyFromUserMetadata(config.svUser)
      }
      scanAggregatesReaderContext = new ScanAggregatesReaderContext(
        clock,
        ledgerClient,
        loggerFactory,
        retryProvider,
        ec,
        Materializer(ac),
        httpClient,
        templateDecoder,
      )
      participantAdminConnection = new ParticipantAdminConnection(
        config.participantClient.adminApi,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        nodeMetrics.grpcClientMetrics,
        retryProvider,
      )
      participantId <- appInitStep("Get participant id") {
        participantAdminConnection.getParticipantId()
      }
      migrationInfo <- appInitStep("Get domain migration info") {
        DomainMigrationInfo.loadFromUserMetadata(
          appInitConnection,
          config.svUser,
        )
      }
      _ = if (config.domainMigrationId != migrationInfo.currentMigrationId) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            s"Migration id ${migrationInfo.currentMigrationId} from the the SV user metadata does not match the configured migration id ${config.domainMigrationId} in the scan app. Please check if the scan app is configured with the correct migration id"
          )
          .asRuntimeException()
      }
      store = ScanStore(
        key = ScanStore.Key(dsoParty = dsoParty),
        storage,
        isFounder = config.isFounder,
        loggerFactory,
        retryProvider,
        { store =>
          ScanAggregatesReader(store, scanAggregatesReaderContext)
        },
        migrationInfo,
        participantId,
      )
      sequencerAdminConnection = new SequencerAdminConnection(
        config.sequencerAdminClient,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        nodeMetrics.grpcClientMetrics,
        retryProvider,
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
          "wait_open_mining",
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
      dsoAnsResolver = new DsoAnsResolver(dsoParty)
      internalHandler = new HttpScanHandler(
        participantAdminConnection,
        store,
        dsoAnsResolver,
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

  protected[this] override def automationServices(st: ScanApp.State) =
    Seq(st.automation)
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
