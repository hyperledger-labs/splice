// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell

import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import com.digitalasset.canton.util.MonadUtil
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.cors
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.lfdecentralizedtrust.splice.admin.api.TraceContextDirectives.withTraceContext
import org.lfdecentralizedtrust.splice.admin.http.{AdminRoutes, HttpErrorHandler}
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  DarResource,
  DarResources,
  Node,
  ParticipantAdminConnection,
  RetryFor,
  SpliceLedgerClient,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.http.v0.splitwell.SplitwellResource
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient.SplitwellDomains
import org.lfdecentralizedtrust.splice.splitwell.admin.http.HttpSplitwellHandler
import org.lfdecentralizedtrust.splice.splitwell.automation.SplitwellAutomationService
import org.lfdecentralizedtrust.splice.splitwell.config.SplitwellAppBackendConfig
import org.lfdecentralizedtrust.splice.splitwell.metrics.SplitwellAppMetrics
import org.lfdecentralizedtrust.splice.splitwell.store.SplitwellStore
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.util.HasHealth

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Splitwell app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SplitwellApp(
    override val name: InstanceName,
    val config: SplitwellAppBackendConfig,
    val amuletAppParameters: SharedSpliceAppParameters,
    storage: DbStorage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    metrics: SplitwellAppMetrics,
    adminRoutes: AdminRoutes,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends Node[SplitwellApp.State, Unit](
      config.providerUser,
      config.participantClient,
      amuletAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      metrics,
    ) {

  override lazy val ports = Map("admin" -> config.adminApi.port)

  override def packagesForJsonDecoding: Seq[DarResource] =
    super.packagesForJsonDecoding ++ DarResources.splitwell.all

  override def preInitializeAfterLedgerConnection(
      connection: BaseLedgerConnection,
      ledgerClient: SpliceLedgerClient,
  )(implicit traceContext: TraceContext) = Future.unit

  override def initialize(
      ledgerClient: SpliceLedgerClient,
      partyId: PartyId,
      preInitializeState: Unit,
  )(implicit traceContext: TraceContext): Future[SplitwellApp.State] = for {
    scanConnection <- appInitStep(s"Get scan connection") {
      ScanConnection.singleCached(
        ledgerClient,
        config.scanClient,
        amuletAppParameters.upgradesConfig,
        clock,
        retryProvider,
        loggerFactory,
      )
    }
    participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      amuletAppParameters.loggingConfig.api,
      loggerFactory,
      metrics.grpcClientMetrics,
      retryProvider,
    )
    participantId <- appInitStep("Get participant id") {
      participantAdminConnection.getParticipantId()
    }
    storeKey = SplitwellStore.Key(providerParty = partyId)
    // TODO(DACH-NY/canton-network-node#9731): get migration id from sponsor sv / scan instead of configuring here
    migrationInfo = DomainMigrationInfo(
      config.domainMigrationId,
      None,
    )
    store = SplitwellStore(
      storeKey,
      storage,
      config.domains,
      loggerFactory,
      retryProvider,
      migrationInfo,
      participantId,
      config.automation.ingestion,
    )
    // splitwell does not need to have UpdateHistory
    automation = new SplitwellAutomationService(
      config.automation,
      clock,
      store,
      storage,
      ledgerClient,
      scanConnection,
      retryProvider,
      config.parameters,
      loggerFactory,
    )
    preferred <- appInitStep(s"Wait for preferred domain connection") {
      store.domains.waitForDomainConnection(config.domains.splitwell.preferred.alias)
    }
    others <- appInitStep(s"Wait for other domain connections") {
      MonadUtil.sequentialTraverse(
        config.domains.splitwell.others
          .map(_.alias)
          .toList
      )(store.domains.waitForDomainConnection(_))
    }
    splitwellDomains = SplitwellDomains(preferred, others)

    _ <- appInitStep(s"Create splitwell rules") {
      createSplitwellRules(splitwellDomains, automation)
    }

    handler = new HttpSplitwellHandler(
      participantAdminConnection,
      SplitwellDomains(preferred, others),
      storeKey.providerParty,
      store,
      loggerFactory,
    )
    route = cors(
      CorsSettings(ac).withExposedHeaders(Seq("traceparent"))
    ) {
      withTraceContext { traceContext =>
        requestLogger(traceContext) {
          HttpErrorHandler(loggerFactory)(traceContext) {
            concat(
              SplitwellResource.routes(handler, _ => provide(traceContext))
            )
          }
        }
      }
    }
    _ = adminRoutes.updateRoute(route)
  } yield {
    SplitwellApp.State(
      automation,
      storage,
      store,
      scanConnection,
      participantAdminConnection,
      loggerFactory.getTracedLogger(SplitwellApp.State.getClass),
      timeouts,
    )
  }

  private def createSplitwellRules(
      domains: SplitwellDomains,
      automation: SplitwellAutomationService,
  )(implicit traceContext: TraceContext): Future[Unit] =
    MonadUtil.sequentialTraverse_((domains.preferred +: domains.others).toList)(
      createSplitwellRules(_, automation)
    )

  private def createSplitwellRules(
      domain: SynchronizerId,
      automation: SplitwellAutomationService,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "splitwell_rules_created",
      s"Wait for splitwell rules to be created for domain $domain",
      automation.store.lookupSplitwellRules(domain).flatMap {
        case QueryResult(offset, None) =>
          automation
            .connection(SpliceLedgerConnectionPriority.Low)
            .submit(
              Seq(automation.store.key.providerParty),
              Seq.empty,
              new splitwellCodegen.SplitwellRules(
                automation.store.key.providerParty.toProtoPrimitive
              ).create,
            )
            .withSynchronizerId(domain)
            .withDedup(
              SpliceLedgerConnection.CommandId(
                "org.lfdecentralizedtrust.splice.splitwell.createSplitwellRules",
                Seq(automation.store.key.providerParty),
                domain.toProtoPrimitive,
              ),
              offset,
            )
            .yieldUnit()
        case QueryResult(_, Some(_)) => Future.unit
      },
      logger,
    )
  }

  override lazy val requiredPackageIds = Set(
    DarResources.splitwell.all
      .find(_.metadata.version == config.requiredDarVersion)
      .getOrElse(
        throw Status.INVALID_ARGUMENT
          .withDescription(s"No splitwell DAR with version ${config.requiredDarVersion} found")
          .asRuntimeException
      )
      .packageId
  )

  protected[this] override def automationServices(st: SplitwellApp.State) =
    Seq(st.automation)
}

object SplitwellApp {
  case class State(
      automation: SplitwellAutomationService,
      storage: DbStorage,
      store: SplitwellStore,
      scanConnection: ScanConnection,
      participantAdminConnection: ParticipantAdminConnection,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      LifeCycle.close(
        automation,
        storage,
        store,
        scanConnection,
        participantAdminConnection,
      )(logger)
  }
}
