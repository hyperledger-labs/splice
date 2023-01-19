package com.daml.network.splitwise

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.admin.grpc.GrpcSplitwiseService
import com.daml.network.splitwise.automation.SplitwiseAutomationService
import com.daml.network.splitwise.config.SplitwiseAppBackendConfig
import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.splitwise.v0.SplitwiseServiceGrpc
import com.daml.network.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
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

/** Class representing a Splitwise app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SplitwiseApp(
    override val name: InstanceName,
    val config: SplitwiseAppBackendConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    adminServerRegistry: CantonMutableHandlerRegistry,
    retryProvider: CoinRetries,
    futureSupervisor: FutureSupervisor,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CoinNode[SplitwiseApp.State](
      config.providerUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      retryProvider,
    ) {

  override lazy val ports = Map("admin" -> config.adminApi.port)

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      party: PartyId,
  ): Future[SplitwiseApp.State] = for {
    store <- Future.successful(SplitwiseStore(party, storage, loggerFactory, futureSupervisor))
    connection = ledgerClient.connection()
    // TODO(M3-82): once we have explicit disclosure: remove the need to fetch these extra readAs rights, which are there to enable using the CoinRules, which are only visible to the validatorParty
    readAs <- connection.getUserReadAs(config.providerUser)
    scanConnection =
      new ScanConnection(
        config.remoteScan.adminApi,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      )
    automation = new SplitwiseAutomationService(
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
    _ <- store.domains.signalWhenConnected(config.domains.splitwise)
  } yield {
    adminServerRegistry
      .addService(
        SplitwiseServiceGrpc.bindService(
          new GrpcSplitwiseService(
            ledgerClient,
            scanConnection,
            party,
            store,
            loggerFactory,
          ),
          ec,
        )
      )
      .discard
    SplitwiseApp.State(
      automation,
      storage,
      store,
      scanConnection,
      loggerFactory.getTracedLogger(SplitwiseApp.State.getClass),
    )
  }

  override lazy val requiredTemplates = Set(splitwiseCodegen.SplitwiseInstall.TEMPLATE_ID)
}

object SplitwiseApp {
  case class State(
      automation: SplitwiseAutomationService,
      storage: Storage,
      store: SplitwiseStore,
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
