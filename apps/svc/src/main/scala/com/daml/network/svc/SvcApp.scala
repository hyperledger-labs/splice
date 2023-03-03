package com.daml.network.svc

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode}
import com.daml.network.svc.admin.grpc.GrpcSvcAppService
import com.daml.network.svc.automation.SvcAutomationService
import com.daml.network.svc.config.SvcAppBackendConfig
import com.daml.network.svc.store.SvcStore
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.api.trace.Tracer

import scala.collection.immutable.Map
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing an SVC app instance. */
class SvcApp(
    override val name: InstanceName,
    val config: SvcAppBackendConfig,
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
    tracer: Tracer,
) extends CoinNode[SvcApp.State](
      config.ledgerApiUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      svcPartyId: PartyId,
  ): Future[SvcApp.State] =
    for {
      store <- Future.successful(
        SvcStore(
          svcPartyId,
          storage,
          config.domains,
          loggerFactory,
          futureSupervisor,
          retryProvider,
        )
      )
      connection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
      // We can't move this to the SV app at the moment because of init order;
      // without uploading this DAR here the `createValidatorRight` step below
      // may fail, leaving the SVC app unitialized, which currently also means
      // that none of the SV apps can boot.
      automation = new SvcAutomationService(
        clock,
        config,
        store,
        ledgerClient,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      domainId <- waitForDomainConnection(store.domains, config.domains.global)
      _ = logger.info(s"SVC App is initialized")
    } yield {
      adminServerRegistry
        .addService(
          SvcServiceGrpc.bindService(
            new GrpcSvcAppService(
              ledgerClient,
              config.ledgerApiUser,
              store,
              domainId,
              loggerFactory,
            ),
            ec,
          )
        )
        .discard
      SvcApp.State(
        storage,
        store,
        automation,
        logger,
      )
    }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  // SVC app uploads package so no dep.
  override lazy val requiredTemplates = Set.empty
}

object SvcApp {
  case class State(
      storage: Storage,
      store: SvcStore,
      automation: SvcAutomationService,
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
