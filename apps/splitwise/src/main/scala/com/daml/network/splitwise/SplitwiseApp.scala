package com.daml.network.splitwise

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.admin.grpc.GrpcSplitwiseService
import com.daml.network.splitwise.config.LocalSplitwiseAppConfig
import com.daml.network.splitwise.store.SplitwiseAppStore
import com.daml.network.splitwise.v0.SplitwiseServiceGrpc
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Splitwise app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SplitwiseApp(
    override val name: InstanceName,
    val config: LocalSplitwiseAppConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    adminServerRegistry: CantonMutableHandlerRegistry,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CoinNode[SplitwiseApp.State](coinAppParameters, loggerFactory, tracerProvider) {

  override val ports = Map("admin" -> config.adminApi.port)

  override def initialize(): Future[SplitwiseApp.State] = for {
    store <- Future.successful(SplitwiseAppStore(storage, loggerFactory))
    ledgerClient =
      createLedgerClient(
        config.remoteParticipant
      )

    scanConnection =
      new ScanConnection(
        config.remoteScan.clientAdminApi,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      )
  } yield {
    adminServerRegistry.addService(
      SplitwiseServiceGrpc.bindService(
        new GrpcSplitwiseService(
          ledgerClient,
          scanConnection,
          config.providerUser,
          loggerFactory,
        ),
        ec,
      )
    )
    SplitwiseApp.State(
      storage,
      store,
      ledgerClient,
      scanConnection,
      loggerFactory.getTracedLogger(SplitwiseApp.State.getClass),
    )
  }
}

object SplitwiseApp {
  case class State(
      storage: Storage,
      store: SplitwiseAppStore,
      ledgerClient: CoinLedgerClient,
      scanConnection: ScanConnection,
      logger: TracedLogger,
  ) extends AutoCloseable {
    override def close() =
      Lifecycle.close(
        storage,
        store,
        ledgerClient,
        scanConnection,
      )(logger)
  }
}
