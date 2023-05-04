package com.daml.network.splitwell

import akka.actor.ActorSystem
import cats.syntax.traverse.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.{CNLedgerClient, CNNode}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwell.admin.api.client.commands.GrpcSplitwellAppClient.SplitwellDomains
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
    val coinAppParameters: SharedCNNodeAppParameters,
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
) extends CNNode[SplitwellApp.State](
      config.providerUser,
      config.participantClient,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  override lazy val ports = Map("admin" -> config.adminApi.port)

  override def initialize(
      ledgerClient: CNLedgerClient,
      party: PartyId,
  ): Future[SplitwellApp.State] = for {
    store <- Future.successful(
      SplitwellStore(party, storage, config.domains, loggerFactory, futureSupervisor, retryProvider)
    )
    scanConnection <-
      ScanConnection(
        ledgerClient,
        config.scanClient,
        clock,
        retryProvider,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      )
    automation = new SplitwellAutomationService(
      config.automation,
      clock,
      store,
      ledgerClient,
      scanConnection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
    _ <- waitForDomainConnection(store.domains, config.domains.global)
    preferred <- waitForDomainConnection(store.domains, config.domains.splitwell.preferred)
    others <- config.domains.splitwell.others.toList.traverse(store.domains.signalWhenConnected(_))
  } yield {
    val splitwellDomains = SplitwellDomains(preferred, others)
    adminServerRegistry
      .addService(
        SplitwellServiceGrpc.bindService(
          new GrpcSplitwellService(
            ledgerClient,
            splitwellDomains,
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
