package com.daml.network.splitwell

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwell.admin.grpc.GrpcSplitwellService
import com.daml.network.splitwell.automation.SplitwellAutomationService
import com.daml.network.splitwell.config.SplitwellAppBackendConfig
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.splitwell.v0.SplitwellServiceGrpc
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

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Splitwell app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SplitwellApp(
    override val name: InstanceName,
    val config: SplitwellAppBackendConfig,
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
) extends CoinNode[SplitwellApp.State](
      config.providerUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  override lazy val ports = Map("admin" -> config.adminApi.port)

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      party: PartyId,
  ): Future[SplitwellApp.State] = for {
    store <- Future.successful(
      SplitwellStore(party, storage, config.domains, loggerFactory, futureSupervisor)
    )
    connection = ledgerClient.connection()
    // TODO(M3-82): once we have explicit disclosure: remove the need to fetch these extra readAs rights, which are there to enable using the CoinRules, which are only visible to the validatorParty
    readAs <- connection.getUserReadAs(config.providerUser)
    scanConnection =
      new ScanConnection(
        config.remoteScan.adminApi,
        clock,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      )
    automation = new SplitwellAutomationService(
      config.automation,
      config.domains,
      clock,
      store,
      ledgerClient,
      readAs,
      scanConnection,
      participantAdminConnection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
    _ <- store.domains.signalWhenConnected(config.domains.global)
    splitwellDomainId <- store.domains.signalWhenConnected(config.domains.splitwell)
  } yield {
    adminServerRegistry
      .addService(
        SplitwellServiceGrpc.bindService(
          new GrpcSplitwellService(
            ledgerClient,
            splitwellDomainId,
            scanConnection,
            party,
            store,
            loggerFactory,
          ),
          ec,
        )
      )
      .discard
    SplitwellApp.State(
      automation,
      storage,
      store,
      scanConnection,
      loggerFactory.getTracedLogger(SplitwellApp.State.getClass),
    )
  }

  override lazy val requiredTemplates = Set(splitwellCodegen.SplitwellInstall.TEMPLATE_ID)
}

object SplitwellApp {
  case class State(
      automation: SplitwellAutomationService,
      storage: Storage,
      store: SplitwellStore,
      scanConnection: ScanConnection,
      logger: TracedLogger,
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      Lifecycle.close(
        automation,
        storage,
        store,
        scanConnection,
      )(logger)
  }
}
