package com.daml.network.sv

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNode, CoinRetries}
import com.daml.network.http.v0.sv.SvResource
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.admin.http.HttpSvHandler
import com.daml.network.sv.automation.SvAutomationService
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.svc.admin.api.client.SvcConnection
import com.daml.network.util.CoinUtil.{defaultCoinConfigSchedule, defaultEnabledChoices}
import com.daml.network.util.{HasHealth, UploadablePackage}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.{AsyncCloseable, FlagCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
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
      storeKey = SvStore.Key(svPartyId, svcPartyId)
      svStore = SvSvStore(storeKey, storage, config.domains, loggerFactory, futureSupervisor)
      svcStore = SvSvcStore(storeKey, storage, config.domains, loggerFactory, futureSupervisor)
      automation = new SvAutomationService(
        clock,
        config,
        svStore,
        svcStore,
        ledgerClient,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      _ <- svStore.domains.signalWhenConnected()
      _ <- svcStore.domains.signalWhenConnected()
      _ <-
        if (config.foundConsortium) {
          foundConsortium(svcStore, ledgerClient, retryProvider, this)
        } else {
          retryProvider.retryForAutomationGrpc(
            "join existing SV consortium",
            joinConsortium(svPartyId),
            this,
          )
        }
      routes = cors() {
        SvResource.routes(
          new HttpSvHandler(
            ledgerClient,
            config.ledgerApiUser,
            svStore,
            svcStore,
            loggerFactory,
          )
          // TODO(M3-46) add client authentication via `AuthExtractor`
        )
      }
      httpConfig = config.adminApi.clientConfig.copy(
        // TODO(#2019) Remove once we disabled Canton's `CommunityAdminServer`.
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
      _ = logger.info(s"SV App is initialized")
    } yield {
      SvApp.State(
        storage,
        svStore,
        svcStore,
        automation,
        binding,
        logger,
        timeouts,
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
      store: SvSvcStore,
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
      _ <- ledgerConnection.uploadDarFile(SvApp.validatorLifecyclePackage)
    } yield ()

  // Create SvcRules and CoinRules and open the first mining round
  private def bootstrapSvc(
      store: SvSvcStore,
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
                    defaultCoinConfigSchedule(
                      config.initialTickDuration,
                      config.initialMaxNumInputs,
                    ),
                    config.coinPrice.bigDecimal,
                    new cn.svcrules.SvcRulesConfig(
                      10
                    ), // TODO(M3-46) handle default config values better
                    defaultEnabledChoices,
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
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      automation: SvAutomationService,
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
        svStore,
        svcStore,
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
  val validatorLifecyclePackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String =
      cn.validatoronboarding.ValidatorOnboarding.COMPANION.TEMPLATE_ID.getPackageId
    lazy val resourcePath: String = "dar/validator-lifecycle-0.1.0.dar"
  }
}
