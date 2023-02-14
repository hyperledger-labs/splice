package com.daml.network.scan

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode, CoinRetries}
import com.daml.network.scan.automation.ScanAutomationService
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.ScanStore
import com.daml.network.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}
import com.digitalasset.canton.lifecycle.AsyncCloseable
import com.daml.network.scan.admin.http.HttpScanHandler
import com.daml.network.http.v0.scan.ScanResource

import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import com.digitalasset.canton.config.ProcessingTimeout

/** Class representing a Scan app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class ScanApp(
    override val name: InstanceName,
    val config: ScanAppBackendConfig,
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
      participantAdminConnection: ParticipantAdminConnection,
      svcParty: PartyId,
  ): Future[ScanApp.State] =
    for {
      store <- Future.successful(
        ScanStore(svcParty, storage, config.domains, loggerFactory, futureSupervisor)
      )
      automation = new ScanAutomationService(
        config.automation,
        clock,
        svcParty,
        ledgerClient,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
        store,
      )
      domainId <- waitForDomainConnection(store.domains, config.domains.global)
      _ <- store.acs(domainId).flatMap(_.signalWhenIngested(OpenMiningRound.COMPANION))

      routes = cors() {
        ScanResource.routes(
          new HttpScanHandler(
            ledgerClient,
            store,
            clock,
            retryProvider,
            loggerFactory,
          )
        )
      }

      httpConfig = config.adminApi.clientConfig.copy(
        port = config.adminApi.port + 1000
      )
      _ = logger.info(s"Starting http server on ${httpConfig}")
      binding <- Http()
        .newServerAt(
          httpConfig.address,
          httpConfig.port.unwrap,
        )
        .bind(
          routes
        )
    } yield {
      ScanApp.State(
        storage,
        store,
        automation,
        binding,
        loggerFactory.getTracedLogger(ScanApp.State.getClass),
        timeouts,
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
      binding: Http.ServerBinding,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  )(implicit el: ErrorLoggingContext)
      extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      Lifecycle.close(
        AsyncCloseable(
          "http binding",
          binding.terminate(timeouts.shutdownNetwork.asFiniteApproximation),
          timeouts.shutdownNetwork.unwrap,
        ),
        storage,
        store,
        automation,
      )(logger)
  }
}
