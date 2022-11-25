package com.daml.network.svc

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.coin.Coin
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNode, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.svc.admin.grpc.GrpcSvcAppService
import com.daml.network.svc.automation.SvcAutomationService
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.svc.store.SvcStore
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.util.CoinUtil.{createValidatorRight, defaultCoinConfig}
import com.daml.network.util.{HasHealth, UploadablePackage}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.{FlagCloseable, Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

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
    val retryProvider: CoinRetries,
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
      CoinRetries(loggerFactory),
    ) {

  override lazy val allocateServiceUser = true

  override def initialize(
      ledgerClient: CoinLedgerClient,
      svcPartyId: PartyId,
  ): Future[SvcApp.State] =
    for {
      store <- Future.successful(SvcStore(svcPartyId, storage, loggerFactory))
      connection = ledgerClient.connection("SvcAppBootstrap")
      _ <- connection.uploadDarFile(SvcApp.coinPackage)
      automation = new SvcAutomationService(
        config.automation,
        coinAppParameters.clockConfig,
        store,
        ledgerClient,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      _ <- SvcApp.setupApp(svcPartyId, connection, logger, store, retryProvider, this)
      _ = logger.info(s"SVC App is initialized")
    } yield {
      adminServerRegistry
        .addService(
          SvcServiceGrpc.bindService(
            new GrpcSvcAppService(ledgerClient, config.damlUser, store, loggerFactory),
            ec,
          )
        )
        .discard
      SvcApp.State(
        storage,
        store,
        automation,
        logger,
      )
    }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  // SVC app uploads package so no dep.
  override lazy val requiredTemplates = Set.empty
}

object SvcApp {
  case class State(
      storage: Storage,
      store: SvcStore,
      automation: SvcAutomationService,
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
  val coinPackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String = Coin.COMPANION.TEMPLATE_ID.getPackageId

    // See `Compile / resourceGenerators` in build.sbt
    lazy val resourcePath: String = "dar/canton-coin-0.1.0.dar"
  }
  private def setupApp(
      svc: PartyId,
      connection: CoinLedgerConnection,
      logger: TracedLogger,
      store: SvcStore,
      retryProvider: CoinRetries,
      flagCloseable: FlagCloseable,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {

    // Create CoinRules and open a first mining round
    val createCoinRulesCmd =
      new cc.coinrules.CoinRules(
        svc.toProtoPrimitive,
        defaultCoinConfig,
        Seq.empty.asJava,
      ).createAnd
        .exerciseCoinRules_MiningRound_Open(
          BigDecimal(1.0).bigDecimal,
          new v1.round.Round(0),
        )
        .commands
        .asScala
        .toSeq
    for {
      _ <- createValidatorRight(
        svc = svc,
        validator = svc,
        user = svc,
        logger = logger,
        connection = connection,
        retryProvider = retryProvider,
        flagCloseable = flagCloseable,
        lookupValidatorRightByParty = store.lookupValidatorRightByParty,
      )
      _ <- retryProvider.retryForAutomation(
        "create coinrules and issuance state",
        store.lookupCoinRules().flatMap {
          case QueryResult(off, None) =>
            connection
              .submitCommandsWithDedup(
                actAs = Seq(svc),
                readAs = Seq.empty,
                commands = createCoinRulesCmd,
                commandId =
                  CoinLedgerConnection.CommandId("com.daml.network.svc.createCoinRules", Seq()),
                deduplicationOffset = off,
              )
          case QueryResult(_, Some(_)) =>
            logger.info("CoinRules already exists, skipping")
            Future.successful(())
        },
        flagCloseable,
      )
    } yield ()
  }

}
