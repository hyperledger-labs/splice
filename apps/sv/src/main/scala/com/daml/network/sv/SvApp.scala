package com.daml.network.sv

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode, CoinRetries}
import com.daml.network.sv.admin.grpc.GrpcSvAppService
import com.daml.network.sv.automation.SvAutomationService
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.sv.store.SvStore
import com.daml.network.sv.v0.SvServiceGrpc
import com.daml.network.svc.admin.api.client.SvcConnection
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

class SvApp(
    override val name: InstanceName,
    val config: LocalSvAppConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    adminServerRegistry: CantonMutableHandlerRegistry,
    val retryProvider: CoinRetries,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CoinNode[SvApp.State](
      config.damlUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      CoinRetries(loggerFactory),
    ) {

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      svPartyId: PartyId,
  ): Future[SvApp.State] =
    for {
      store <- Future.successful(SvStore(svPartyId, storage, loggerFactory))
      connection = ledgerClient.connection()
      automation = new SvAutomationService(
        clock,
        config,
        store,
        ledgerClient,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      _ <- store.domains.signalWhenConnected()
      // TODO(#2241) move check whether we are already part of the SVC to here
      _ <- retryProvider.retryForAutomationGrpc("joinConsortium", joinConsortium(svPartyId), this)
      _ = logger.info(s"SV App is initialized")
    } yield {
      adminServerRegistry
        .addService(
          SvServiceGrpc.bindService(
            new GrpcSvAppService(ledgerClient, config.damlUser, store, loggerFactory),
            ec,
          )
        )
        .discard
      SvApp.State(
        storage,
        store,
        automation,
        logger,
      )
    }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  // SV app uploads package so no dep.
  override lazy val requiredTemplates = Set.empty

  def joinConsortium(svPartyId: PartyId) = {
    val svcConnection = new SvcConnection(
      config.remoteSvc.clientAdminApi,
      coinAppParameters.processingTimeouts,
      loggerFactory,
    )
    svcConnection.joinConsortium(svPartyId).andThen(_ => svcConnection.close())
  }
}

object SvApp {
  case class State(
      storage: Storage,
      store: SvStore,
      automation: SvAutomationService,
      logger: TracedLogger,
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      Lifecycle.close(
        storage,
        store,
        automation,
      )(logger)

  }
}
