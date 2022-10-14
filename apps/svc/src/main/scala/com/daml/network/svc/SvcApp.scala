package com.daml.network.svc

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.codegen.CC
import com.daml.network.codegen.CC.Coin.Coin
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNode}
import com.daml.network.svc.admin.SvcLogCollectionService
import com.daml.network.svc.admin.grpc.GrpcSvcAppService
import com.daml.network.svc.automation.SvcAutomationService
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.svc.store.SvcStore
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.util.CoinUtil.{
  ExplicitDisclosureWorkaround,
  defaultCoinConfig,
  recordValidatorOfCommand,
}
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

/** Class representing an SVC app instance. */
class SvcApp(
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
) extends CoinNode[SvcApp.State](
      config.damlUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  override val allocateServiceUser = true

  override def initialize(
      ledgerClient: CoinLedgerClient,
      svcPartyId: PartyId,
  ): Future[SvcApp.State] =
    for {
      store <- Future.successful(SvcStore(svcPartyId, storage, loggerFactory))
      connection = ledgerClient.connection("SvcAppBootstrap")
      _ <- connection.uploadDarFile(SvcApp.coinPackage)
      _ <- SvcApp.setupApp(svcPartyId, connection)
      _ = logger.info(s"SVC App is initialized")
      logCollection = new SvcLogCollectionService(
        svcPartyId,
        ledgerClient,
        loggerFactory,
        timeouts,
        store,
      )
      automation = new SvcAutomationService(
        store,
        ledgerClient,
        this,
        loggerFactory,
        timeouts,
      )
    } yield {
      adminServerRegistry
        .addService(
          SvcServiceGrpc.bindService(
            new GrpcSvcAppService(ledgerClient, config.damlUser, store.events, loggerFactory),
            ec,
          )
        )
        .discard
      SvcApp.State(
        storage,
        store,
        logCollection,
        automation,
        logger,
      )
    }

  override val ports = Map("admin" -> config.adminApi.port)

  // SVC app uploads package so no dep.
  override val requiredTemplates = Set.empty
}

object SvcApp {
  case class State(
      storage: Storage,
      store: SvcStore,
      logCollection: SvcLogCollectionService,
      automation: SvcAutomationService,
      logger: TracedLogger,
  ) extends AutoCloseable {
    override def close(): Unit =
      Lifecycle.close(
        storage,
        store,
        logCollection,
        automation,
      )(logger)

  }
  val coinPackage: UploadablePackage = new UploadablePackage {
    lazy val coinTemplateId: com.daml.ledger.api.v1.value.Identifier =
      ApiTypes.TemplateId.unwrap(Coin.id)

    lazy val packageId: String = coinTemplateId.packageId

    // See `Compile / resourceGenerators` in build.sbt
    lazy val resourcePath: String = "dar/canton-coin-0.1.0.dar"
  }
  private def setupApp(
      svc: PartyId,
      connection: CoinLedgerConnection,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    // Needed for ED workaround
    val recordUserHostedAtCmd = ExplicitDisclosureWorkaround.recordUserHostedAtCommand(svc, svc)
    // The SVC is its own validator
    val recordValidatorOfCmd = recordValidatorOfCommand(svc, svc, svc)

    // Create an IssuanceState
    val createIssuanceStateCmd =
      CC.Coin
        .IssuanceState(
          svc = svc.toPrim,
          obs = svc.toPrim,
          currentRound = CC.Round.Round(-1),
        )
        .create
        .command

    // Create CoinRules and open a first mining round
    val createCoinRulesCmd =
      CC.CoinRules
        .CoinRules(
          svc = svc.toPrim,
          obs = svc.toPrim,
          config = defaultCoinConfig,
        )
        .createAnd
        .exerciseCoinRules_MiningRound_Open(coinPrice = 1.0)
        .command
    for {
      _ <- connection.ignoreDuplicateKeyErrors(
        connection.submitCommand(
          actAs = Seq(svc),
          readAs = Seq.empty,
          command = Seq(recordUserHostedAtCmd),
        ),
        s"SVC.setupApp($svc, ...)",
      )
      _ <- connection.ignoreDuplicateKeyErrors(
        //
        connection
          .submitCommand(
            actAs = Seq(svc),
            readAs = Seq.empty,
            command = Seq(recordValidatorOfCmd),
          ),
        s"SVC.setupApp($svc, ...)",
      )
      _ <- connection.ignoreDuplicateKeyErrors(
        connection.submitCommand(
          actAs = Seq(svc),
          readAs = Seq.empty,
          command = Seq(createIssuanceStateCmd),
        ),
        s"SVC.setupApp($svc, ...)",
      )
      // NOTE: this must be the last initialization to perform, as otherwise validators might be onboarded too early
      // in the SvcAutomationService's CoinRulesRequest handler
      _ <- connection.ignoreDuplicateKeyErrors(
        connection.submitCommand(
          actAs = Seq(svc),
          readAs = Seq.empty,
          command = Seq(createCoinRulesCmd),
        ),
        s"SVC.setupApp($svc, ...)",
      )
    } yield ()
  }

}
