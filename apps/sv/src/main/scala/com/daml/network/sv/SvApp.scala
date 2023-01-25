package com.daml.network.sv

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNode, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.admin.grpc.GrpcSvAppService
import com.daml.network.sv.automation.SvAutomationService
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.sv.store.SvStore
import com.daml.network.sv.v0.SvServiceGrpc
import com.daml.network.svc.admin.api.client.SvcConnection
import com.daml.network.util.CoinUtil.defaultCoinConfig
import com.daml.network.util.{HasHealth, UploadablePackage}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.{FlagCloseable, Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TracerProvider
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

class SvApp(
    override val name: InstanceName,
    val config: LocalSvAppConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    adminServerRegistry: CantonMutableHandlerRegistry,
    val retryProvider: CoinRetries,
    futureSupervisor: FutureSupervisor,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CoinNode[SvApp.State](
      config.ledgerApiUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      CoinRetries(loggerFactory),
    ) {

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      svPartyId: PartyId,
  ): Future[SvApp.State] =
    for {
      svcPartyId <- retryProvider.retryForAutomationGrpc("get SVC party ID", getSvcPartyId, this)
      svStoreKey = SvStore.Key(svPartyId, svcPartyId)
      store = SvStore(svStoreKey, storage, loggerFactory, futureSupervisor)
      automation = new SvAutomationService(
        clock,
        config,
        store,
        ledgerClient,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      _ <- store.domains.signalWhenConnected()
      _ <-
        if (config.foundConsortium) {
          foundConsortium(store, ledgerClient, retryProvider, this)
        } else {
          retryProvider.retryForAutomationGrpc(
            "join existing SV consortium",
            joinConsortium(svPartyId),
            this,
          )
        }
      _ = logger.info(s"SV App is initialized")
    } yield {
      adminServerRegistry
        .addService(
          SvServiceGrpc.bindService(
            new GrpcSvAppService(ledgerClient, config.ledgerApiUser, store, loggerFactory),
            ec,
          )
        )
        .discard
      SvApp.State(
        storage,
        store,
        automation,
        logger,
      )
    }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  // SV app uploads package so no dep.
  override lazy val requiredTemplates = Set.empty

  private def getSvcPartyId: Future[PartyId] = {
    // From SVC app for now
    val svcConnection = new SvcConnection(
      config.remoteSvc.clientAdminApi,
      coinAppParameters.processingTimeouts,
      loggerFactory,
    )
    svcConnection
      .getDebugInfo()
      .map(_.svcParty)
      .andThen(_ => svcConnection.close())
  }

  private def foundConsortium(
      store: SvStore,
      ledgerClient: CoinLedgerClient,
      retryProvider: CoinRetries,
      flagCloseable: FlagCloseable,
  ): Future[Unit] = {
    for {
      domainId <- store.domains.getUniqueDomainId()
      ledgerConnection = ledgerClient.connection()
      _ <- uploadDars(ledgerConnection)
      _ <- retryProvider.retryForAutomationGrpc(
        "create CoinRules and issuance state",
        createCoinRules(store, ledgerConnection, domainId),
        flagCloseable,
      )
      _ <- retryProvider.retryForAutomationGrpc(
        "create SvcRules",
        createSvcRules(store, ledgerConnection, domainId, 1),
        flagCloseable,
      )
      // make sure we can't act as the svc party anymore now that the `SvcRules` are set up
      _ <- waiveSvcRights(store.key.svcParty, ledgerConnection)
    } yield ()
  }

  private def uploadDars(ledgerConnection: CoinLedgerConnection): Future[Unit] =
    for {
      _ <- ledgerConnection.uploadDarFile(SvApp.coinPackage)
      _ <- ledgerConnection.uploadDarFile(SvApp.svcGovernancePackage)
    } yield ()

  // Create CoinRules and open the first mining round
  private def createCoinRules(
      store: SvStore,
      ledgerConnection: CoinLedgerConnection,
      domainId: DomainId,
  ): Future[Unit] =
    for {
      _ <- store.lookupCoinRulesWithOffset().flatMap {
        case QueryResult(off, None) =>
          ledgerConnection
            .submitCommands(
              actAs = Seq(store.key.svcParty),
              readAs = Seq.empty,
              commands = new cc.coin.CoinRules(
                store.key.svcParty.toProtoPrimitive,
                defaultCoinConfig(config.initialTickDuration, config.initialMaxNumInputs),
                Seq.empty.asJava,
              ).createAnd
                .exerciseCoinRules_Bootstrap_Rounds(
                  config.coinPrice.bigDecimal
                )
                .commands
                .asScala
                .toSeq,
              commandId =
                CoinLedgerConnection.CommandId("com.daml.network.svc.createCoinRules", Seq()),
              deduplicationOffset = off,
              domainId = domainId,
            )
        case QueryResult(_, Some(_)) =>
          logger.info("CoinRules already exists, skipping")
          Future.successful(())
      }
    } yield ()

  private def createSvcRules(
      store: SvStore,
      ledgerConnection: CoinLedgerConnection,
      domainId: DomainId,
      roundNumber: Int,
  ): Future[Unit] = {
    val svcParty = store.key.svcParty
    val svParty = store.key.svParty
    for {
      _ <- store.lookupSvcRulesWithOffset().flatMap {
        case QueryResult(off, None) =>
          logger.info("SvcRules don't exist; creating with party as leader")
          val round = new cc.api.v1.round.Round(roundNumber);
          ledgerConnection
            .submitCommands(
              actAs = Seq(svcParty),
              readAs = Seq.empty,
              commands = new cn.svcrules.SvcRules(
                svcParty.toProtoPrimitive,
                0,
                round,
                java.util.Map.of(svParty.toProtoPrimitive, new cn.svcrules.MemberInfo(round)),
                svParty.toProtoPrimitive,
              ).create.commands.asScala.toSeq,
              commandId =
                CoinLedgerConnection.CommandId("com.daml.network.svc.createSvcRules", Seq()),
              deduplicationOffset = off,
              domainId = domainId,
            )
        case QueryResult(_, Some(svcRules)) =>
          if (svcRules.payload.members.keySet.contains(svParty.toProtoPrimitive)) {
            logger
              .info(show"SvcRules exist and party is member; doing nothing. SvcRules: $svcRules")
            Future.successful(())
          } else {
            sys.error(
              "SvcRules exist but party tasked with founding it isn't member. " +
                show"Is more than one SV app configured to `found-consortium`? SvcRules: $svcRules"
            )
          }
      }
    } yield ()
  }

  private def waiveSvcRights(
      svcParty: PartyId,
      ledgerConnection: CoinLedgerConnection,
  ): Future[Unit] =
    for {
      _ <- ledgerConnection.grantUserRights(config.ledgerApiUser, Seq.empty, Seq(svcParty))
      _ <- ledgerConnection.revokeUserRights(config.ledgerApiUser, Seq(svcParty), Seq.empty)
    } yield ()

  private def joinConsortium(svPartyId: PartyId): Future[Unit] = {
    val svcConnection = new SvcConnection(
      config.remoteSvc.clientAdminApi,
      coinAppParameters.processingTimeouts,
      loggerFactory,
    )
    svcConnection
      .joinConsortium(svPartyId)
      .andThen(_ => svcConnection.close())
  }
}

object SvApp {
  case class State(
      storage: Storage,
      store: SvStore,
      automation: SvAutomationService,
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
    lazy val packageId: String = cc.coin.Coin.COMPANION.TEMPLATE_ID.getPackageId

    // See `Compile / resourceGenerators` in build.sbt
    lazy val resourcePath: String = "dar/canton-coin-0.1.0.dar"
  }
  val svcGovernancePackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String = cn.svcrules.SvcRules.COMPANION.TEMPLATE_ID.getPackageId
    lazy val resourcePath: String = "dar/svc-governance-0.1.0.dar"
  }
}
