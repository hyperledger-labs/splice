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
        "boostrapping SVC",
        bootstrapSvc(store, ledgerConnection, domainId),
        flagCloseable,
      )
      // make sure we can't act as the svc party anymore now that `SvcBootstrap` is done
      _ <- waiveSvcRights(store.key.svcParty, ledgerConnection)
    } yield ()
  }

  private def uploadDars(ledgerConnection: CoinLedgerConnection): Future[Unit] =
    for {
      _ <- ledgerConnection.uploadDarFile(SvApp.coinPackage)
      _ <- ledgerConnection.uploadDarFile(SvApp.svcGovernancePackage)
    } yield ()

  // Create SvcRules and CoinRules and open the first mining round
  private def bootstrapSvc(
      store: SvStore,
      ledgerConnection: CoinLedgerConnection,
      domainId: DomainId,
  ): Future[Unit] = {
    val svcParty = store.key.svcParty
    val svParty = store.key.svParty
    for {
      coinRules <- store.lookupCoinRules()
      _ <- store.lookupSvcRulesWithOffset().flatMap {
        case QueryResult(off, None) => {
          coinRules match {
            case Some(coinRules) =>
              sys.error(
                "A CoinRules contract was found but no SvcRules contract exists. " +
                  show"This should never happen.\nCoinRules: $coinRules"
              )
            case None =>
              ledgerConnection
                .submitCommands(
                  actAs = Seq(svcParty),
                  readAs = Seq.empty,
                  commands = new cn.svcbootstrap.SvcBootstrap(
                    svcParty.toProtoPrimitive,
                    svParty.toProtoPrimitive,
                    defaultCoinConfig(config.initialTickDuration, config.initialMaxNumInputs),
                    config.coinPrice.bigDecimal,
                  ).createAnd
                    .exerciseSvcBootstrap_Bootstrap()
                    .commands
                    .asScala
                    .toSeq,
                  commandId = CoinLedgerConnection
                    .CommandId("com.daml.network.svc.executeSvcBootstrap", Seq()),
                  deduplicationOffset = off,
                  domainId = domainId,
                )
          }
        }
        case QueryResult(_, Some(svcRules)) =>
          coinRules match {
            case Some(coinRules) => {
              if (svcRules.payload.members.keySet.contains(svParty.toProtoPrimitive)) {
                logger.info(
                  "CoinRules and SvcRules already exist and founding party is an SVC member; doing nothing." +
                    show"\nCoinRules: $coinRules\nSvcRules: $svcRules"
                )
                Future.successful(())
              } else {
                sys.error(
                  "CoinRules and SvcRules already exist but party tasked with founding the SVC isn't member." +
                    "Is more than one SV app configured to `found-consortium`?" +
                    show"\nCoinRules: $coinRules\nSvcRules: $svcRules"
                )
              }
            }
            case None =>
              sys.error(
                "An SvcRules contract was found but no CoinRules contract exists. " +
                  show"This should never happen.\nSvcRules: $svcRules"
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
