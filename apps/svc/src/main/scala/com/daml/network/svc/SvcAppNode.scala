package com.daml.network.svc

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode}
import com.daml.network.svc.admin.SvcAutomationService
import com.daml.network.svc.admin.grpc.GrpcSvcAppService
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.svc.store.SvcAppStore
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing an SVC app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SvcAppNode(
    override val name: InstanceName,
    val config: LocalSvcAppConfig,
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
) extends CoinNode[SvcAppNode.State](coinAppParameters, loggerFactory, tracerProvider) {

  override def initialize(): Future[SvcAppNode.State] =
    for {
      store <- Future.successful(SvcAppStore(storage, loggerFactory))

      ledgerClient =
        createLedgerClient(config.remoteParticipant)

      connection = ledgerClient.connection("SvcAppBootstrap")
      _ <- connection.uploadDarFile(CoinUtil)
      svcPartyId <- connection.getOrAllocateParty(config.damlUser)
      _ = logger.info(s"Allocated SVC party $svcPartyId")
      _ <- CoinUtil.setupApp(svcPartyId, connection)
      _ = logger.info(s"SVC App is initialized")
      automation = new SvcAutomationService(
        svcPartyId,
        ledgerClient,
        loggerFactory,
        timeouts,
        store,
      )
    } yield {
      adminServerRegistry.addService(
        SvcServiceGrpc.bindService(
          new GrpcSvcAppService(ledgerClient, config.damlUser, store, loggerFactory),
          ec,
        )
      )
      SvcAppNode.State(
        storage,
        store,
        automation,
        ledgerClient,
        logger,
      )
    }

  override val ports = Map("admin" -> config.adminApi.port)
}

object SvcAppNode {
  case class State(
      storage: Storage,
      store: SvcAppStore,
      automation: SvcAutomationService,
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
