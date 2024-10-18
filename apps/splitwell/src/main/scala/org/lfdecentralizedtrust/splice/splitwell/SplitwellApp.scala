// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Directives.*
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.lfdecentralizedtrust.splice.admin.api.TraceContextDirectives.withTraceContext
import org.lfdecentralizedtrust.splice.admin.http.{AdminRoutes, HttpErrorHandler}
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.environment.{
  SpliceLedgerClient,
  SpliceLedgerConnection,
  Node,
  DarResource,
  DarResources,
  ParticipantAdminConnection,
  RetryFor,
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
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.cors
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Splitwell app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SplitwellApp(
    override val name: InstanceName,
    val config: SplitwellAppBackendConfig,
    val amuletAppParameters: SharedSpliceAppParameters,
    storage: Storage,
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
) extends Node[SplitwellApp.State](
      config.providerUser,
      config.participantClient,
      amuletAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      metrics,
    ) {

  override lazy val ports = Map("admin" -> config.adminApi.port)

  override def packages: Seq[DarResource] = super.packages ++ DarResources.splitwell.all

  override def initialize(
      ledgerClient: SpliceLedgerClient,
      partyId: PartyId,
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
    // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
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
    )
    automation = new SplitwellAutomationService(
      config.automation,
      clock,
      store,
      ledgerClient,
      scanConnection,
      config.supportsSoftDomainMigrationPoc,
      retryProvider,
      loggerFactory,
    )
    preferred <- appInitStep(s"Wait for preferred domain connection") {
      store.domains.waitForDomainConnection(config.domains.splitwell.preferred.alias)
    }
    others <- appInitStep(s"Wait for other domain connections") {
      config.domains.splitwell.others
        .map(_.alias)
        .toList
        .traverse(store.domains.waitForDomainConnection(_))
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
    (domains.preferred +: domains.others).toList.traverse_(createSplitwellRules(_, automation))

  private def createSplitwellRules(
      domain: DomainId,
      automation: SplitwellAutomationService,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "splitwell_rules_created",
      s"Wait for splitwell rules to be created for domain $domain",
      automation.store.lookupSplitwellRules(domain).flatMap {
        case QueryResult(offset, None) =>
          automation.connection
            .submit(
              Seq(automation.store.key.providerParty),
              Seq.empty,
              new splitwellCodegen.SplitwellRules(
                automation.store.key.providerParty.toProtoPrimitive
              ).create,
            )
            .withDomainId(domain)
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

  override lazy val requiredPackageIds = Set(DarResources.splitwell.bootstrap.packageId)

  protected[this] override def automationServices(st: SplitwellApp.State) =
    Seq(st.automation)
}

object SplitwellApp {
  case class State(
      automation: SplitwellAutomationService,
      storage: Storage,
      store: SplitwellStore,
      scanConnection: ScanConnection,
      participantAdminConnection: ParticipantAdminConnection,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      Lifecycle.close(
        automation,
        storage,
        store,
        scanConnection,
        participantAdminConnection,
      )(logger)
  }
}
