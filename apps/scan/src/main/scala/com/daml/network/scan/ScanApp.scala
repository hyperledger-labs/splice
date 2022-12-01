package com.daml.network.scan

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode, CoinRetries}
import com.daml.network.scan.admin.grpc.GrpcScanService
import com.daml.network.scan.automation.ScanAutomationService
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.scan.store.ScanStore
import com.daml.network.scan.v0.ScanServiceGrpc
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

/** Class representing a Scan app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class ScanApp(
    override val name: InstanceName,
    val config: LocalScanAppConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    adminServerRegistry: CantonMutableHandlerRegistry,
    retryProvider: CoinRetries,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CoinNode[ScanApp.State](
      config.svcUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      CoinRetries(loggerFactory),
    ) {

  override def initialize(
      ledgerClient: CoinLedgerClient,
      svcParty: PartyId,
  ): Future[ScanApp.State] =
    for {
      store <- Future.successful(ScanStore(svcParty, storage, loggerFactory, timeouts))
      automation = new ScanAutomationService(
        config.automation,
        coinAppParameters.clockConfig,
        svcParty,
        ledgerClient,
        retryProvider,
        loggerFactory,
        timeouts,
        store,
      )
    } yield {
      adminServerRegistry
        .addService(
          ScanServiceGrpc.bindService(
            new GrpcScanService(
              ledgerClient,
              store,
              coinAppParameters.clockConfig,
              retryProvider,
              loggerFactory,
            ),
            ec,
          )
        )
        .discard
      ScanApp.State(
        storage,
        store,
        automation,
        loggerFactory.getTracedLogger(ScanApp.State.getClass),
      )
    }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  override lazy val requiredTemplates = Set(coinCodegen.Coin.TEMPLATE_ID)
}

object ScanApp {

  case class State(
      storage: Storage,
      store: ScanStore,
      automation: ScanAutomationService,
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
