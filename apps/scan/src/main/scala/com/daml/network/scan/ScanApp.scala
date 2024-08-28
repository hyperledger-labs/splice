// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Directives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.TraceContextDirectives.withTraceContext
import com.daml.network.admin.http.{AdminRoutes, HttpErrorHandler}
import com.daml.network.codegen.java.splice.round as roundCodegen
import com.daml.network.config.SharedSpliceAppParameters
import com.daml.network.environment.{
  DarResources,
  Node,
  ParticipantAdminConnection,
  RetryFor,
  SequencerAdminConnection,
  SpliceLedgerClient,
}
import com.daml.network.http.v0.external.scan.ScanResource as ExternalScanResource
import com.daml.network.http.v0.scan.ScanResource as InternalScanResource
import com.daml.network.http.v0.scan_soft_domain_migration_poc.ScanSoftDomainMigrationPocResource
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.scan.admin.http.{
  HttpExternalScanHandler,
  HttpScanHandler,
  HttpScanSoftDomainMigrationPocHandler,
}
import com.daml.network.scan.automation.ScanAutomationService
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.metrics.ScanAppMetrics
import com.daml.network.scan.store.{AcsSnapshotStore, ScanStore}
import com.daml.network.scan.store.db.{ScanAggregatesReader, ScanAggregatesReaderContext}
import com.daml.network.scan.dso.DsoAnsResolver
import com.daml.network.store.PageLimit
import com.daml.network.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
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
    val amuletAppParameters: SharedSpliceAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    nodeMetrics: ScanAppMetrics,
    adminRoutes: AdminRoutes,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends Node[ScanApp.State](
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
      ledgerClient: SpliceLedgerClient,
      // The primary party in scan as that points to the SV party
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
        amuletAppParameters.upgradesConfig,
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
      migrationInfo <- appInitStep(s"Get domain migration info from ${config.svUser}") {
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
        isFirstSv = config.isFirstSv,
        loggerFactory,
        retryProvider,
        { store =>
          ScanAggregatesReader(store, scanAggregatesReaderContext)
        },
        migrationInfo,
        participantId,
      )
      acsSnapshotStore = AcsSnapshotStore(
        storage,
        store.updateHistory,
        migrationInfo.currentMigrationId,
        loggerFactory,
      )
      sequencerAdminConnection = new SequencerAdminConnection(
        config.sequencerAdminClient,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        nodeMetrics.grpcClientMetrics,
        retryProvider,
      )
      automation = new ScanAutomationService(
        config,
        clock,
        ledgerClient,
        retryProvider,
        loggerFactory,
        store,
        acsSnapshotStore,
        config.ingestFromParticipantBegin,
        config.ingestUpdateHistoryFromParticipantBegin,
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
      dsoAnsResolver = new DsoAnsResolver(
        dsoParty,
        config.spliceInstanceNames.nameServiceNameAcronym.toLowerCase(),
      )
      internalHandler = new HttpScanHandler(
        serviceUserPrimaryParty,
        config.svUser,
        config.spliceInstanceNames,
        participantAdminConnection,
        store,
        acsSnapshotStore,
        dsoAnsResolver,
        config.miningRoundsCacheTimeToLiveOverride,
        config.enableForcedAcsSnapshots,
        clock,
        loggerFactory,
      )

      externalHandler = new HttpExternalScanHandler(
        store,
        sequencerAdminConnection,
        loggerFactory,
      )

      softDomainMigrationPocHandler =
        if (config.supportsSoftDomainMigrationPoc)
          Seq(
            new HttpScanSoftDomainMigrationPocHandler(
              participantAdminConnection,
              store,
              loggerFactory,
              amuletAppParameters,
              nodeMetrics,
              config.synchronizers,
              retryProvider,
            )
          )
        else Seq.empty

      route = cors(
        CorsSettings(ac).withExposedHeaders(Seq("traceparent"))
      ) {
        withTraceContext { traceContext =>
          {

            requestLogger(traceContext) {
              HttpErrorHandler(loggerFactory)(traceContext) {
                concat(
                  (InternalScanResource.routes(internalHandler, _ => provide(traceContext)) +:
                    ExternalScanResource.routes(externalHandler, _ => provide(traceContext)) +:
                    softDomainMigrationPocHandler.map(handler =>
                      ScanSoftDomainMigrationPocResource.routes(handler, _ => provide(traceContext))
                    ))*
                )
              }
            }
          }
        }
      }
      _ = adminRoutes.updateRoute(route)
    } yield {
      ScanApp.State(
        participantAdminConnection,
        sequencerAdminConnection,
        storage,
        store,
        automation,
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
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      Lifecycle.close(
        automation,
        store,
        storage,
        sequencerAdminConnection,
        participantAdminConnection,
      )(logger)
  }
}
