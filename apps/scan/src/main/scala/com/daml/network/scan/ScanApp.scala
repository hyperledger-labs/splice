package com.daml.network.scan

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.codegen.CC as coinCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode, CoinRetries, JavaCoinLedgerClient}
import com.daml.network.scan.admin.grpc.GrpcScanService
import com.daml.network.scan.automation.ScanAutomationService
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.scan.store.ScanCCHistoryStore
import com.daml.network.scan.v0.ScanServiceGrpc
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
      javaLedgerClient: JavaCoinLedgerClient,
      svcParty: PartyId,
  ): Future[ScanApp.State] =
    for {
      store <- Future.successful(ScanCCHistoryStore(storage, loggerFactory))
      automation = new ScanAutomationService(
        svcParty,
        ledgerClient,
        loggerFactory,
        timeouts,
        store,
      )
    } yield {
      adminServerRegistry
        .addService(
          ScanServiceGrpc.bindService(
            new GrpcScanService(ledgerClient, config.svcUser, store, loggerFactory),
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

  override lazy val requiredTemplates = Set(coinCodegen.Coin.Coin)
}

object ScanApp {

  case class State(
      storage: Storage,
      store: ScanCCHistoryStore,
      automation: ScanAutomationService,
      logger: TracedLogger,
  ) extends AutoCloseable {
    override def close() =
      Lifecycle.close(
        storage,
        store,
        automation,
      )(logger)
  }
}
