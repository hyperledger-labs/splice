package com.daml.network.scan

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode}
import com.daml.network.scan.admin.ScanAutomationService
import com.daml.network.scan.admin.grpc.GrpcScanService
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.scan.store.ScanCCHistoryStore
import com.daml.network.scan.v0.ScanServiceGrpc
import com.daml.network.util.Proto
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TracerProvider
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import com.digitalasset.canton.util.retry.{Backoff, Forever, Success}
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.duration._
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
) extends CoinNode[ScanApp.State](coinAppParameters, loggerFactory, tracerProvider) {

  override def initialize(): Future[ScanApp.State] =
    for {
      store <- Future.successful(ScanCCHistoryStore(storage, loggerFactory))
      ledgerClient =
        createLedgerClient(
          config.remoteParticipant
        )
      policy = Backoff(
        logger,
        this,
        Forever,
        1.seconds,
        10.seconds,
        s"Get SVC party within ScanApp $name initialization",
      )
      scanServiceGrpc = new GrpcScanService(ledgerClient, config.svcUser, store, loggerFactory)
      svcParty <- policy(scanServiceGrpc.getSvcPartyId(Empty()), AllExnRetryable)(
        Success.always,
        ec,
        traceContext,
      ).map(resp => Proto.tryDecode(Proto.Party)(resp.svcPartyId))
      automation = new ScanAutomationService(
        svcParty,
        ledgerClient,
        loggerFactory,
        timeouts,
        store,
      )
    } yield {
      adminServerRegistry.addService(
        ScanServiceGrpc.bindService(
          scanServiceGrpc,
          ec,
        )
      )
      ScanApp.State(
        storage,
        store,
        automation,
        ledgerClient,
        loggerFactory.getTracedLogger(ScanApp.State.getClass),
      )
    }

  override val ports = Map("admin" -> config.adminApi.port)
}

object ScanApp {

  case class State(
      storage: Storage,
      store: ScanCCHistoryStore,
      automation: ScanAutomationService,
      ledgerClient: CoinLedgerClient,
      logger: TracedLogger,
  ) extends AutoCloseable {
    override def close() =
      Lifecycle.close(
        storage,
        store,
        automation,
        ledgerClient,
      )(logger)
  }
}
