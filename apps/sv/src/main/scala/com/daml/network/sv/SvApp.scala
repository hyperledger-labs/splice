package com.daml.network.sv

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Directives.*
import cats.implicits.catsSyntaxTuple2Semigroupal
import cats.syntax.either.*
import cats.syntax.foldable.*
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.TraceContextDirectives.newTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.auth.{AuthConfig, HMACVerifier, RSAVerifier}
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_RemoveMember
import com.daml.network.codegen.java.cn.svcrules.{
  Reason,
  SvcRules_RemoveMember,
  SvcRules_RequestVote,
}
import com.daml.network.codegen.java.cn.svonboarding.SvOnboardingConfirmed
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.config.{NetworkAppClientConfig, SharedCNNodeAppParameters}
import com.daml.network.environment.*
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.http.v0.commonAdmin.CommonAdminResource
import com.daml.network.http.v0.sv.SvResource
import com.daml.network.http.v0.svAdmin.SvAdminResource
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.admin.http.{HttpSvAdminHandler, HttpSvHandler}
import com.daml.network.sv.auth.SvAuthExtractor
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.cometbft.{
  CometBftClient,
  CometBftConnectionConfig,
  CometBftHttpRpcClient,
  CometBftNode,
}
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.init.FoundingNodeInitializer
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil}
import com.daml.network.svc.admin.api.client.SvcConnection
import com.daml.network.util.{Contract, HasHealth, UploadablePackage}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{
  CommunityCryptoConfig,
  NonNegativeFiniteDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.time.EnrichedDurations.*
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.security.interfaces.ECPrivateKey
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SvApp(
    override val name: InstanceName,
    val config: SvAppBackendConfig,
    val coinAppParameters: SharedCNNodeAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CNNode[SvApp.State](
      config.ledgerApiUser,
      config.participantClient,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  private val cometBftConfig = config.cometBftConfig
    .filter(_.enabled)

  override def initialize(ledgerClient: CNLedgerClient, svPartyId: PartyId): Future[SvApp.State] = {
    val participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      timeouts,
      loggerFactory,
    )
    val localDomainNode = config.xNodes
      .flatMap(_.domain)
      .map(config =>
        new LocalDomainNode(
          new SequencerAdminConnection(
            config.sequencer.adminApi,
            timeouts,
            loggerFactory,
          ),
          new MediatorAdminConnection(
            config.mediator.adminApi,
            timeouts,
            loggerFactory,
          ),
          config.parameters
            .toStaticDomainParameters(CommunityCryptoConfig())
            .valueOr(err =>
              throw new IllegalArgumentException(s"Invalid domain parameters config: $err")
            ),
          config.sequencer.publicApi,
          timeouts,
          loggerFactory,
          retryProvider,
        )
      )
    initialize(
      participantAdminConnection,
      localDomainNode,
      ledgerClient,
      svPartyId,
    )
      .recoverWith { case err =>
        // TODO(#3474) Replace this by a more general solution for closing resources on
        // init failures.
        participantAdminConnection.close()
        localDomainNode.foreach(_.close())
        Future.failed(err)
      }
  }

  private def initialize(
      participantAdminConnection: ParticipantAdminConnection,
      localDomainNode: Option[LocalDomainNode],
      ledgerClient: CNLedgerClient,
      svPartyId: PartyId,
  ): Future[SvApp.State] = {
    for {
      // It is possible that the participant left disconnected to domains due to party migration failure in the last SV startup.
      // reconnect all domains at the beginning of SV initialization just in case.
      _ <- participantAdminConnection.reconnectAllDomains()
      // TODO(#3856): find a better way to get the SVC party ID
      svcPartyId <- retryProvider.retryForAutomation("get SVC party ID", getSvcPartyId, logger)
      storeKey = SvStore.Key(svPartyId, svcPartyId)
      svStore = newSvStore(storeKey)
      svAutomation = newSvSvAutomationService(
        svStore,
        ledgerClient,
      )
      globalDomain <- waitForDomainConnection(svStore.domains, config.domains.global.alias)
      svcPartyHosting = newSvcPartyHosting(
        storeKey,
        participantAdminConnection,
      )
      cometBftClient = newCometBftClient
      (svcStore, svcAutomation, svcRulesLock) <- ensureOnboarded(
        svAutomation,
        ledgerClient,
        participantAdminConnection,
        localDomainNode,
        svcPartyHosting,
        globalDomain,
        cometBftClient,
      )
      _ <- expectConfiguredValidatorOnboardings(
        svAutomation,
        globalDomain,
        clock,
      )
      _ <- approveConfiguredSvIdentities(svAutomation, globalDomain)
      isDevNet <- retryProvider.retryForAutomation(
        "get CoinRules to determine if we are in a DevNet",
        svcStore.getCoinRules().map(coinRules => coinRules.payload.isDevNet),
        logger,
      )

      _ <- config.initialCoinPriceVote
        .map(
          ensureCoinPriceVoteHasCoinPrice(
            _,
            svcAutomation,
            globalDomain,
            logger,
          )
        )
        .getOrElse(Future.unit)

      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl) => new RSAVerifier(audience, jwksUrl)
      }

      handler = new HttpSvHandler(
        globalDomain,
        config.ledgerApiUser,
        svAutomation,
        svcAutomation,
        isDevNet,
        clock,
        participantAdminConnection,
        svcRulesLock,
        localDomainNode,
        retryProvider,
        svcPartyHosting,
        loggerFactory,
      )

      adminHandler = new HttpSvAdminHandler(
        globalDomain,
        svAutomation,
        svcAutomation,
        cometBftClient,
        localDomainNode,
        clock,
        loggerFactory,
      )

      // TODO(#3467) -- attach handler before app initialization, i.e. in bootstrap
      commonAdminHandler = new HttpAdminHandler(
        status
          .map(CNNodeStatus.fromNodeStatus)
          .map(NodeStatus.Success(_)),
        loggerFactory,
      )

      routes = cors(
        CorsSettings(ac).withAllowedMethods(
          List(
            HttpMethods.DELETE,
            HttpMethods.GET,
            HttpMethods.POST,
            HttpMethods.PUT,
            HttpMethods.HEAD,
            HttpMethods.OPTIONS,
          )
        )
      ) {
        newTraceContext { traceContext =>
          requestLogger(traceContext) {
            HttpErrorHandler(loggerFactory)(traceContext) {
              concat(
                SvResource.routes(
                  handler,
                  _ => provide(()),
                ),
                SvAdminResource.routes(
                  adminHandler,
                  SvAuthExtractor(
                    verifier,
                    svPartyId,
                    svAutomation.connection,
                    loggerFactory,
                    "canton network sv admin realm",
                  ),
                ),
                CommonAdminResource.routes(commonAdminHandler),
              )
            }
          }
        }

      }
      _ = logger.info(s"Starting http server on ${config.adminApi.clientConfig}")
      binding <- Http()
        .newServerAt(
          config.adminApi.clientConfig.address,
          config.adminApi.clientConfig.port.unwrap,
        )
        .bind(
          routes
        )
      _ = logger.info(s"SV App is initialized")
    } yield {
      SvApp.State(
        participantAdminConnection,
        localDomainNode,
        storage,
        svStore,
        svcStore,
        svAutomation,
        svcAutomation,
        binding,
        logger,
        timeouts,
      )
    }
  }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  // The SV app uploads package but there is a bug in Canton around concurrent package uploads
  // so we wait for the validator app to finish the upload first. See https://github.com/DACH-NY/canton/issues/12993
  override lazy val requiredTemplates =
    config.onboarding match {
      // The validator app waits on the sv app so if we wait on the dar upload here, we create
      // a circular dependency.
      // The validator app waits on the svc party before uploading a DAR so there is no chance of the
      // upload happening concurrently here.
      case _: SvOnboardingConfig.FoundCollective => Set.empty
      case _ =>
        Set(cc.coin.Coin.TEMPLATE_ID) ++
          (if (config.enableCoinRulesUpgrade) Set(ccV1Test.coin.CoinRulesV1Test.TEMPLATE_ID)
           else Set.empty)
    }

  private def ensureOnboarded(
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      ledgerClient: CNLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      localDomainNode: Option[LocalDomainNode],
      svcPartyHosting: SvcPartyHosting,
      globalDomain: DomainId,
      cometBftClient: Option[CometBftClient],
  ): Future[(SvSvcStore, SvSvcAutomationService, SvcRulesLock)] = {
    val svStore = svStoreWithIngestion.store
    val cometBftNode = (cometBftClient, cometBftConfig).mapN((client, config) =>
      new CometBftNode(client, config, loggerFactory)
    )
    for {
      participantId <- retryProvider.retryForAutomation(
        "get Participant ID",
        getParticipantId(participantAdminConnection),
        logger,
      )
      svcPartyIsAuthorized <- svcPartyHosting.svcPartyIsAuthorized(
        globalDomain,
        participantId,
      )
      (svcStore, svcAutomation) <-
        if (svcPartyIsAuthorized) {
          logger.info("SVC party is authorized to our participant.")
          for {
            _ <- svStoreWithIngestion.connection.grantUserRights(
              config.ledgerApiUser,
              Seq.empty,
              Seq(svStore.key.svcParty),
            )
            svcStore = newSvcStore(svStore.key)
            svcAutomation =
              newSvSvcAutomationService(svStore, svcStore, ledgerClient, cometBftNode)
            _ <- waitForDomainConnection(svcStore.domains, config.domains.global.alias)
            onboarded <- isOnboarded(svcStore)
            _ <-
              if (onboarded) {
                logger.info(
                  "We can see the SvcRules and are listed as an SVC member => already onboarded."
                )
                Future.successful(())
              } else {
                logger.info("Starting onboarding (without SVC party migration).")
                startOnboardingWithSvcPartyHosted(
                  svcAutomation,
                  globalDomain,
                  cometBftNode,
                )
              }
          } yield (svcStore, svcAutomation)
        } else {
          logger.info(
            "The SVC party is not authorized to our participant. " +
              "Starting onboarding with SVC party migration."
          )
          startOnboardingWithSvcPartyMigration(
            svStoreWithIngestion,
            participantId,
            globalDomain,
            ledgerClient,
            svcPartyHosting,
            cometBftNode,
          )
        }
      _ <- waitForSvcMembership(svcStore)
      svcRulesLock = new SvcRulesLock(globalDomain, svcAutomation, retryProvider, loggerFactory)
      _ <- withLocalDomainNode(localDomainNode) { case (localDomainNode, svConnection) =>
        for {
          _ <- localDomainNode.onboardLocalSequencerIfRequired(
            config.domains.global.alias,
            globalDomain,
            participantAdminConnection,
            svConnection,
          )
          _ <- localDomainNode.onboardLocalMediatorIfRequired(
            globalDomain,
            participantAdminConnection,
            svConnection,
            svcRulesLock,
          )
        } yield ()
      }
    } yield (svcStore, svcAutomation, svcRulesLock)
  }

  private def withLocalDomainNode[A](
      localDomainNodeO: Option[LocalDomainNode]
  )(f: (LocalDomainNode, SvConnection) => Future[A]): Future[Unit] =
    (for {
      localDomainNode <- localDomainNodeO
      svConfig <- config.onboarding match {
        case conf: SvOnboardingConfig.JoinWithKey => Some(conf.svClient.adminApi)
        case _: SvOnboardingConfig.FoundCollective => None
      }
    } yield (localDomainNode, svConfig)).traverse_ { case (localDomainNode, svConfig) =>
      SvConnection(
        svConfig,
        retryProvider,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      ).flatMap { svConnection =>
        f(localDomainNode, svConnection).andThen(_ => svConnection.close())
      }
    }

  private def startOnboardingWithSvcPartyMigration(
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      participantId: ParticipantId,
      globalDomain: DomainId,
      ledgerClient: CNLedgerClient,
      svcPartyHosting: SvcPartyHosting,
      cometBftNode: Option[CometBftNode],
  ): Future[(SvSvcStore, SvSvcAutomationService)] = {
    val svStore = svStoreWithIngestion.store
    config.onboarding match {
      case SvOnboardingConfig.JoinWithKey(name, svClient, publicKey, privateKey) =>
        SvUtil.keyPairMatches(publicKey, privateKey) match {
          case Right(privateKey_) =>
            for {
              _ <- uploadDars(svStoreWithIngestion.connection)
              _ <- waitForValidatorLicense(svStore)
              _ <- requestOnboarding(
                svClient.adminApi,
                name,
                svStore.key.svParty,
                publicKey,
                svStore.key.svcParty,
                privateKey_,
              )
              // Wait on the SV store because the SVC party is not yet onboarded.
              _ <- waitForSvOnboardingConfirmed(svStore)
              _ <- startHostingSvcPartyInParticipant(
                globalDomain,
                participantId,
                svcPartyHosting,
                svStore.key.svParty,
              )
              _ <- svStoreWithIngestion.connection.grantUserRights(
                config.ledgerApiUser,
                Seq.empty,
                Seq(svStore.key.svcParty),
              )
              _ = logger.info(s"granted ${config.ledgerApiUser} readAs rights for svcParty")
              svcStore = newSvcStore(svStore.key)
              svcAutomation = newSvSvcAutomationService(
                svStore,
                svcStore,
                ledgerClient,
                cometBftNode,
              )
              _ <- waitForDomainConnection(svcStore.domains, config.domains.global.alias)
              _ <- addConfirmedMemberToSvc(
                svcAutomation,
                globalDomain,
              )
            } yield (svcStore, svcAutomation)
          case Left(reason) => sys.error(s"Failed parsing provided keys: $reason")
        }
      case _ => sys.error(s"only JoinWithKey is expected")
    }
  }

  private def startOnboardingWithSvcPartyHosted(
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      globalDomain: DomainId,
      cometBftNode: Option[CometBftNode],
  ): Future[Unit] = {
    val svcStore = svcStoreWithIngestion.store
    config.onboarding match {
      case foundingConfig: SvOnboardingConfig.FoundCollective =>
        val initializer = new FoundingNodeInitializer(
          svcStoreWithIngestion,
          config,
          foundingConfig,
          globalDomain,
          cometBftNode,
          loggerFactory,
          retryProvider,
        )
        for {
          _ <- uploadDars(svcStoreWithIngestion.connection)
          _ <- initializer.foundCollective()
        } yield ()
      case SvOnboardingConfig.JoinWithKey(name, svClient, publicKey, privateKey) =>
        SvUtil.keyPairMatches(publicKey, privateKey) match {
          case Right(privateKey_) =>
            for {
              _ <- requestOnboarding(
                svClient.adminApi,
                name,
                svcStore.key.svParty,
                publicKey,
                svcStore.key.svcParty,
                privateKey_,
              )
              _ <- addConfirmedMemberToSvc(
                svcStoreWithIngestion,
                globalDomain,
              )
            } yield ()
          case Left(reason) => sys.error(s"Failed parsing provided keys: $reason")
        }
    }
  }

  private def startHostingSvcPartyInParticipant(
      domainId: DomainId,
      participantId: ParticipantId,
      svcPartyHosting: SvcPartyHosting,
      svParty: PartyId,
  ): Future[Unit] = {
    svcPartyHosting
      .start(domainId, participantId, svParty)
      .map(
        _.getOrElse(
          sys.error(s"Failed to host svc party on participant $participantId")
        )
      )
  }

  private def getSvcPartyId: Future[PartyId] = {
    // From SVC app for now
    val svcConnection = new SvcConnection(
      config.svcClient.clientAdminApi,
      coinAppParameters.processingTimeouts,
      loggerFactory,
    )
    svcConnection
      .getDebugInfo()
      .map(_.svcParty)
      .andThen(_ => svcConnection.close())
  }

  private def getParticipantId(
      participantAdminConnection: ParticipantAdminConnection
  ): Future[ParticipantId] = {
    participantAdminConnection.getParticipantId(config.xNodes.isDefined)
  }

  private def newSvStore(key: SvStore.Key) = SvSvStore(
    key,
    storage,
    config.domains,
    loggerFactory,
    futureSupervisor,
    retryProvider,
  )

  private def newSvcStore(key: SvStore.Key) = SvSvcStore(
    key,
    storage,
    config,
    loggerFactory,
    futureSupervisor,
    retryProvider,
  )

  private def newSvSvAutomationService(
      svStore: SvSvStore,
      ledgerClient: CNLedgerClient,
  ) =
    new SvSvAutomationService(
      clock,
      config,
      svStore,
      ledgerClient,
      retryProvider,
      loggerFactory,
      timeouts,
    )

  private def newSvSvcAutomationService(
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      ledgerClient: CNLedgerClient,
      cometBftNode: Option[CometBftNode],
  ) =
    new SvSvcAutomationService(
      clock,
      config,
      svStore,
      svcStore,
      ledgerClient,
      retryProvider,
      cometBftNode,
      loggerFactory,
      timeouts,
    )

  private def newSvcPartyHosting(
      storeKey: SvStore.Key,
      participantAdminConnection: ParticipantAdminConnection,
  ) = new SvcPartyHosting(
    config.onboarding,
    participantAdminConnection,
    storeKey.svcParty,
    config.xNodes.isDefined,
    coinAppParameters,
    retryProvider,
    loggerFactory,
  )

  private def newCometBftClient = {
    cometBftConfig
      .map(connectionConfig =>
        new CometBftClient(
          new CometBftHttpRpcClient(
            CometBftConnectionConfig(connectionConfig.connectionUri),
            loggerFactory,
          ),
          loggerFactory,
        )
      )
  }

  private def addConfirmedMemberToSvc(
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      domainId: DomainId,
  ): Future[Unit] = {
    val svcStore = svcStoreWithIngestion.store
    for {
      // Wait on the SVC store to make sure that we atomically see either the SvOnboardingConfirmed contract
      // or the SvcRules contract.
      _ <- waitForSvOnboardingConfirmed(svcStore)
      _ <- retryProvider.retryForAutomation(
        "add member to Svc",
        for {
          svcRules <- svcStore.getSvcRules()
          openMiningRounds <- svcStore.getOpenMiningRoundTriple()
          svOnboardingConfirmedOpt <- svcStore.lookupSvOnboardingConfirmedByParty(
            svcStore.key.svParty
          )
          svIsSvcMember = svcRules.payload.members.asScala
            .contains(svcStore.key.svParty.toProtoPrimitive)
          _ <- svOnboardingConfirmedOpt match {
            case None =>
              if (svIsSvcMember) {
                logger.info(s"SV is already a member of the SVC")
                Future.unit
              } else {
                val msg =
                  "SV is not a member of the SVC but there is also no confirmed onboarding, giving up"
                logger.error(msg)
                Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
              }
            case Some(confirmed) =>
              if (svIsSvcMember) {
                logger.info("SvOnboardingConfirmed exists but SV is already a member of the SVC")
                Future.unit
              } else {
                val cmd = svcRules.contractId.exerciseSvcRules_AddConfirmedMember(
                  svcStore.key.svParty.toProtoPrimitive,
                  confirmed.contractId,
                  openMiningRounds.oldest.contractId,
                  openMiningRounds.middle.contractId,
                  openMiningRounds.newest.contractId,
                )
                svcStoreWithIngestion.connection.submitCommandsNoDedup(
                  Seq(svcStore.key.svParty),
                  Seq(svcStore.key.svcParty),
                  commands = cmd.commands.asScala.toSeq,
                  domainId = domainId,
                )
              }
          }
        } yield (),
        logger,
      )
    } yield ()
  }

  private def isOnboarded(svcStore: SvSvcStore): Future[Boolean] = for {
    svcRules <- svcStore.lookupSvcRules()
  } yield svcRules.exists(_.payload.members.keySet.contains(svcStore.key.svParty.toProtoPrimitive))

  private def waitForValidatorLicense(svStore: SvSvStore): Future[
    Contract[cc.validatorlicense.ValidatorLicense.ContractId, cc.validatorlicense.ValidatorLicense]
  ] = {
    logger.info(s"Waiting for ValidatorLicense contract to be created for ${svStore.key.svParty}")
    // If the validator license contract gets created after we disconnected from the domain, Canton blows up during the SVC party migration
    // because the contract gets added both via the party migration and through the regular event stream for the SV party which is an observer.
    // Therefore, we let the validator app do its thing first.
    retryProvider.retryForAutomation(
      "Wait for ValidatorLicense contract",
      svStore.getValidatorLicense(),
      logger,
    )
  }

  private def waitForSvOnboardingConfirmed(
      svStore: SvSvStore
  ): Future[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]] =
    waitForSvOnboardingConfirmed(() => svStore.lookupSvOnboardingConfirmed())

  private def waitForSvOnboardingConfirmed(
      svcStore: SvSvcStore
  ): Future[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]] =
    waitForSvOnboardingConfirmed(() =>
      svcStore.lookupSvOnboardingConfirmedByParty(svcStore.key.svParty)
    )

  private def waitForSvOnboardingConfirmed(
      lookupSvOnboardingConfirmed: () => Future[
        Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]
      ]
  ): Future[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]] = {
    logger.info(
      s"Waiting for SvOnboardingConfirmed contract to be created"
    )
    retryProvider.retryForAutomation(
      "Wait for SVC SvOnboardingConfirmed contract",
      for {
        svOnboardingConfirmedOpt <- lookupSvOnboardingConfirmed()
        svOnboardingConfirmed <- svOnboardingConfirmedOpt match {
          case Some(sc) =>
            logger.info("svOnboardingConfirmed found, done waiting")
            Future.successful(sc)
          case None =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(
                s"SvOnboardingConfirmed contract not found yet"
              )
            )
        }
      } yield svOnboardingConfirmed,
      logger,
    )
  }

  private def waitForSvcMembership(svcStore: SvSvcStore): Future[Unit] = {
    logger.info("Waiting for SvcRules to become visible and list us as a member")
    retryProvider.retryForAutomation(
      "Wait for SVC membership",
      for {
        svcRules <- svcStore.lookupSvcRules()
        _ <- svcRules match {
          case Some(c) =>
            if (SvApp.isSvcMemberParty(svcStore.key.svParty, c)) {
              logger.info("SvcRules found and we are member, done waiting")
              Future.successful(())
            } else {
              throw new StatusRuntimeException(
                Status.FAILED_PRECONDITION.withDescription(
                  s"SvcRules found but ${svcStore.key.svParty} is not a member"
                )
              )
            }
          case None =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(s"SvcRules contract not found yet")
            )
        }
      } yield (),
      logger,
    )
  }

  private def uploadDars(
      ledgerConnection: CNLedgerConnection
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    for {
      _ <- ledgerConnection.uploadDarFile(SvApp.coinPackage)
      _ <- ledgerConnection.uploadDarFile(SvApp.svcGovernancePackage)
      _ <- ledgerConnection.uploadDarFile(SvApp.validatorLifecyclePackage)
      _ <- ledgerConnection.uploadDarFile(SvApp.directoryPackage)
      _ <-
        if (config.enableCoinRulesUpgrade)
          ledgerConnection.uploadDarFile(SvApp.coinV1TestPackage)
        else
          Future.successful(())
    } yield ()
  }

  private def requestOnboarding(
      sponsorConfig: NetworkAppClientConfig,
      name: String,
      partyId: PartyId,
      publicKey: String,
      svcPartyId: PartyId,
      privateKey: ECPrivateKey,
  ): Future[Unit] = {
    SvOnboardingToken(name, publicKey, partyId, svcPartyId).signAndEncode(privateKey) match {
      case Right(token) =>
        logger.info(s"Requesting to be onboarded via SV at: ${sponsorConfig.url}")
        retryProvider.retryForAutomation(
          "request onboarding",
          SvConnection(
            sponsorConfig,
            retryProvider,
            coinAppParameters.processingTimeouts,
            loggerFactory,
          ).flatMap { svConnection =>
            svConnection
              .startSvOnboarding(token)
              .andThen(_ => svConnection.close())
          },
          logger,
        )
      case Left(error) =>
        Future.failed(
          Status.INTERNAL
            .withDescription(s"Could not create onboarding token: $error")
            .asRuntimeException()
        )
    }
  }

  private def expectConfiguredValidatorOnboardings(
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      globalDomain: DomainId,
      clock: Clock,
  ): Future[List[Unit]] = {
    if (
      config.expectedValidatorOnboardings
        .map(_.secret)
        .toSet
        .size != config.expectedValidatorOnboardings.size
    ) {
      sys.error("Expected onboarding secrets must be unique! Check your SV app config.")
    }
    Future.traverse(config.expectedValidatorOnboardings)(c =>
      expectConfiguredValidatorOnboarding(
        c.secret,
        c.expiresIn,
        svStoreWithIngestion,
        globalDomain,
        clock,
      )
    )
  }

  private def expectConfiguredValidatorOnboarding(
      secret: String,
      expiresIn: NonNegativeFiniteDuration,
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      globalDomain: DomainId,
      clock: Clock,
  ): Future[Unit] =
    retryProvider.retryForAutomation(
      "Create ValidatorOnboarding contract for preconfigured secret",
      SvApp
        .prepareValidatorOnboarding(
          secret,
          expiresIn,
          svStoreWithIngestion,
          globalDomain,
          clock,
          logger,
        )
        .map {
          case Left(reason) => logger.info(s"Did not prepare validator onboarding: $reason")
          case Right(()) => ()
        },
      logger,
    )

  private def approveConfiguredSvIdentities(
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      globalDomain: DomainId,
  ): Future[List[Unit]] = {
    if (
      config.approvedSvIdentities.map(_.publicKey).toSet.size != config.approvedSvIdentities.size
    ) {
      sys.error("Approved SV keys must be unique! Check your SV app config.")
    }
    if (config.approvedSvIdentities.map(_.name).toSet.size != config.approvedSvIdentities.size) {
      sys.error("Approved SV names must be unique! Check your SV app config.")
    }
    Future.traverse(config.approvedSvIdentities)(c =>
      approveConfiguredSvIdentity(c.name, c.publicKey, svStoreWithIngestion, globalDomain)
    )
  }

  private def approveConfiguredSvIdentity(
      name: String,
      key: String,
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      globalDomain: DomainId,
  ): Future[Unit] =
    retryProvider.retryForAutomation(
      "Create ApprovedSvIdentity contract for preconfigured SV identity",
      SvApp.approveSvIdentity(name, key, svStoreWithIngestion, globalDomain, logger).map {
        case Left(reason) => logger.info(s"Failed to approve SV identity: $reason")
        case Right(()) => ()
      },
      logger,
    )

  private def ensureCoinPriceVoteHasCoinPrice(
      defaultCoinPriceVote: BigDecimal,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      globalDomain: DomainId,
      logger: TracedLogger,
  ): Future[Either[String, Unit]] =
    svcStoreWithIngestion.store.lookupCoinPriceVoteByThisSv().flatMap {
      case Some(vote) if vote.payload.coinPrice.toScala.isDefined =>
        logger.info(s"A coin price vote with a defined coin price already exists")
        Future.successful(Right(()))
      case _ =>
        retryProvider.retryForAutomation(
          "Update coin price vote to configured initial coin price vote",
          SvApp
            .updateCoinPriceVote(
              defaultCoinPriceVote,
              svcStoreWithIngestion,
              globalDomain,
              logger,
            ),
          logger,
        )
    }
}

object SvApp {
  case class State(
      participantAdminConnection: ParticipantAdminConnection,
      localDomainNode: Option[LocalDomainNode],
      storage: Storage,
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      svAutomation: SvSvAutomationService,
      svcAutomation: SvSvcAutomationService,
      binding: Http.ServerBinding,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  )(implicit el: ErrorLoggingContext)
      extends FlagCloseableAsync
      with HasHealth {
    override def isHealthy: Boolean =
      storage.isActive && svAutomation.isHealthy && svcAutomation.isHealthy

    override def closeAsync(): Seq[AsyncOrSyncCloseable] =
      Seq(
        AsyncCloseable(
          "http binding",
          binding.terminate(timeouts.shutdownNetwork.asFiniteApproximation),
          timeouts.shutdownNetwork.unwrap,
        ),
        SyncCloseable("storage", storage.close()),
        SyncCloseable("sv store", svStore.close()),
        SyncCloseable("svc store", svcStore.close()),
        SyncCloseable("sv automation", svAutomation.close()),
        SyncCloseable("svc automation", svcAutomation.close()),
        SyncCloseable(
          s"Domain connections",
          localDomainNode.foreach(_.close()),
        ),
        SyncCloseable(
          s"Participant Admin connection",
          participantAdminConnection.close(),
        ),
      )
  }

  def prepareValidatorOnboarding(
      secret: String,
      expiresIn: NonNegativeFiniteDuration,
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      globalDomain: DomainId,
      clock: Clock,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Either[String, Unit]] = {
    val svStore = svStoreWithIngestion.store
    val svParty = svStore.key.svParty
    val validatorOnboarding = new cn.validatoronboarding.ValidatorOnboarding(
      svParty.toProtoPrimitive,
      secret,
      (clock.now + expiresIn.toInternal).toInstant,
    ).create.commands.asScala.toSeq
    for {
      res <- svStore.lookupUsedSecretWithOffset(secret).flatMap {
        case QueryResult(_, Some(usedSecret)) => {
          val validator = usedSecret.payload.validator
          Future.successful(
            Left(s"This secret has already been used before, for onboarding validator $validator")
          )
        }
        case QueryResult(offset, None) => {
          svStore.lookupValidatorOnboardingBySecretWithOffset(secret).flatMap {
            case QueryResult(_, Some(_)) => {
              Future.successful(
                Left("A validator onboarding contract with this secret already exists.")
              )
            }
            case QueryResult(_, None) => {
              svStoreWithIngestion.connection
                .submitCommands(
                  actAs = Seq(svParty),
                  readAs = Seq.empty,
                  commands = validatorOnboarding,
                  commandId = CNLedgerConnection
                    .CommandId(
                      "com.daml.network.sv.expectValidatorOnboarding",
                      Seq(svParty),
                      secret, // not a leak as this gets hashed before it's used
                    ),
                  deduplicationOffset = offset,
                  domainId = globalDomain,
                )
                .map(_ => {
                  logger.info("Created new ValidatorOnboarding contract.")
                  Right(())
                })
            }
          }
        }
      }
    } yield res
  }

  def updateCoinPriceVote(
      desiredCoinPrice: BigDecimal,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      globalDomain: DomainId,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Either[String, Unit]] = {
    val svcStore = svcStoreWithIngestion.store
    svcStore.lookupCoinPriceVoteByThisSv().flatMap {
      case Some(vote) if vote.payload.coinPrice.toScala.contains(desiredCoinPrice.bigDecimal) =>
        logger.info(s"Coin price vote is already set to $desiredCoinPrice")
        Future.successful(Right(()))
      case Some(vote) =>
        for {
          svcRules <- svcStore.getSvcRules()
          cmd = svcRules.contractId.exerciseSvcRules_UpdateCoinPriceVote(
            svcStore.key.svParty.toProtoPrimitive,
            vote.contractId,
            desiredCoinPrice.bigDecimal,
          )
          _ <- svcStoreWithIngestion.connection.submitCommandsNoDedup(
            actAs = Seq(svcStore.key.svParty),
            readAs = Seq(svcStore.key.svcParty),
            commands = cmd.commands().asScala.toSeq,
            domainId = globalDomain,
          )
        } yield Right(())
      case None =>
        Future.successful(
          Left(
            s"No cc price vote contract found for this SV. It is not expected as it should be created when the SV was added to SVC,"
          )
        )
    }
  }

  private def parseVoteRequestAction(value: String): Either[String, ARC_SvcRules] = {
    val jsonDic = ujson.read(value)
    jsonDic("action").str match {
      case "SRARC_RemoveMember" =>
        Right(
          new ARC_SvcRules(new SRARC_RemoveMember(new SvcRules_RemoveMember(jsonDic("member").str)))
        )
      case _ => Left("Action not defined")
    }
  }

  def createVoteRequest(
      requester: String,
      action: String,
      reasonUrl: String,
      reasonDescription: String,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      globalDomain: DomainId,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Either[String, Unit]] = {
    parseVoteRequestAction(action) match {
      case Left(_) =>
        Future.successful(
          Left(
            s"The action could not be created."
          )
        )
      case Right(action) =>
        svcStoreWithIngestion.store.lookupVoteRequestByThisSvAndActionWithOffset(action).flatMap {
          case QueryResult(_, Some(vote)) =>
            Future.successful(
              Left(s"This vote request has already been created ${vote.contractId}.")
            )
          case QueryResult(offset, None) =>
            for {
              svcRules <- svcStoreWithIngestion.store.getSvcRules()
              reason = new Reason(reasonUrl, reasonDescription)
              request = new SvcRules_RequestVote(requester, action, reason)
              cmd = svcRules.contractId.exerciseSvcRules_RequestVote(request)
              _ <- svcStoreWithIngestion.connection.submitCommands(
                actAs = Seq(svcStoreWithIngestion.store.key.svParty),
                readAs = Seq(svcStoreWithIngestion.store.key.svcParty),
                commands = cmd.commands().asScala.toSeq,
                commandId = CNLedgerConnection.CommandId(
                  "com.daml.network.sv.requestVote",
                  Seq(
                    svcStoreWithIngestion.store.key.svcParty,
                    svcStoreWithIngestion.store.key.svParty,
                  ),
                  action.toString,
                ),
                deduplicationOffset = offset,
                domainId = globalDomain,
              )
            } yield Right(())
        }
    }
  }

  def castVote(
      voteRequestCid: cn.svcrules.VoteRequest.ContractId,
      isAccepted: Boolean,
      reasonUrl: String,
      reasonDescription: String,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      globalDomain: DomainId,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Either[String, cn.svcrules.Vote.ContractId]] = {
    svcStoreWithIngestion.store
      .lookupVoteByThisSvAndVoteRequestWithOffset(voteRequestCid)
      .flatMap {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            Left(s"This vote has already been created for vote request $voteRequestCid.")
          )
        case QueryResult(offset, None) =>
          for {
            svcRules <- svcStoreWithIngestion.store.getSvcRules()
            reason = new Reason(reasonUrl, reasonDescription)
            cmd = svcRules.contractId
              .exerciseSvcRules_CastVote(
                voteRequestCid,
                svcStoreWithIngestion.store.key.svParty.toProtoPrimitive,
                isAccepted,
                reason,
              )
            res <- svcStoreWithIngestion.connection.submitWithResult(
              actAs = Seq(svcStoreWithIngestion.store.key.svParty),
              readAs = Seq(svcStoreWithIngestion.store.key.svcParty),
              update = cmd,
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.sv.castVote",
                Seq(
                  svcStoreWithIngestion.store.key.svcParty,
                  svcStoreWithIngestion.store.key.svParty,
                ),
                voteRequestCid.contractId,
              ),
              deduplicationConfig = DedupOffset(
                offset = offset
              ),
              domainId = globalDomain,
            )
          } yield Right(res.exerciseResult)
      }
  }

  def updateVote(
      voteCid: cn.svcrules.Vote.ContractId,
      isAccepted: Boolean,
      reasonUrl: String,
      reasonDescription: String,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      globalDomain: DomainId,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Option[cn.svcrules.Vote.ContractId]] = {
    val reason = new Reason(reasonUrl, reasonDescription)
    svcStoreWithIngestion.store
      .lookupVoteById(voteCid)
      .flatMap {
        case None =>
          // The vote of contract id voteCid not found
          Future.successful(None)
        case Some(vote) if vote.payload.accept == isAccepted && vote.payload.reason == reason =>
          logger.info(s"The vote has already been updated to these value ${vote.payload}.")
          Future.successful(Some(voteCid))
        case Some(_) =>
          for {
            svcRules <- svcStoreWithIngestion.store.getSvcRules()
            cmd = svcRules.contractId
              .exerciseSvcRules_UpdateVote(
                voteCid,
                svcStoreWithIngestion.store.key.svParty.toProtoPrimitive,
                isAccepted,
                reason,
              )
            res <- svcStoreWithIngestion.connection.submitWithResultNoDedup(
              actAs = Seq(svcStoreWithIngestion.store.key.svParty),
              readAs = Seq(svcStoreWithIngestion.store.key.svcParty),
              update = cmd,
              domainId = globalDomain,
            )
          } yield Some(res.exerciseResult)
      }
  }

  private[sv] def approveSvIdentity(
      name: String,
      key: String,
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      globalDomain: DomainId,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Either[String, Unit]] = {
    val svParty = svStoreWithIngestion.store.key.svParty
    val approvedSvIdentity = new cn.svonboarding.ApprovedSvIdentity(
      svParty.toProtoPrimitive,
      name,
      key,
    ).create.commands.asScala.toSeq
    SvUtil.parsePublicKey(key) match {
      case Left(error) => Future.successful(Left(error))
      case Right(_) =>
        for {
          res <- svStoreWithIngestion.store.lookupApprovedSvIdentityByNameWithOffset(name).flatMap {
            case QueryResult(_, Some(id)) => {
              Future.successful(
                Left(if (id.payload.candidateKey == key) {
                  s"The SV identitiy ($name:$key) is already approved."
                } else {
                  s"Tried to approve SV identity ($name:$key), but name $name " +
                    s"is already approved with key ${id.payload.candidateKey}."
                })
              )
            }
            case QueryResult(offset, None) =>
              for {
                _ <- svStoreWithIngestion.connection
                  .submitCommandsTransaction(
                    actAs = Seq(svParty),
                    readAs = Seq.empty,
                    commands = approvedSvIdentity,
                    commandId = CNLedgerConnection
                      .CommandId(
                        "com.daml.network.sv.approveSvIdentity",
                        Seq(svParty),
                        s"$key",
                      ),
                    deduplicationOffset = offset,
                    domainId = globalDomain,
                  )
              } yield {
                logger.info("Created new ApprovedSvIdentity contract.")
                Right(())
              }
          }
        } yield res
    }
  }

  private[sv] def isApprovedSvIdentity(
      candidateName: String,
      candidateParty: PartyId,
      rawToken: String,
      svStore: SvSvStore,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Either[String, (PartyId, String)]] = {

    // We want to make sure that:
    // 1. we log warnings whenever an auth check fails
    // 2. details about the fails are not communicated to requesters
    def authFailure(reason: String, details: String): Left[String, (PartyId, String)] = {
      logger.warn(s"SV candidate authentication failure: $reason ($details)")
      Left(reason)
    }

    svStore
      .lookupApprovedSvIdentityByName(candidateName)
      .map(approvedSvO =>
        for {
          approvedSv <- approvedSvO
            .toRight(s"no matching approved SV identity found for $candidateName")
          token <- SvOnboardingToken.verifyAndDecode(rawToken)
          _ <-
            if (candidateName == token.candidateName) Right(())
            else
              authFailure(
                "provided candidate name doesn't match name in token",
                s"$candidateName != ${token.candidateName}",
              )
          _ <-
            if (token.candidateKey == approvedSv.payload.candidateKey) Right(())
            else
              authFailure(
                "candidate key doesn't match approved key",
                s"${token.candidateKey} != ${approvedSv.payload.candidateKey}",
              )
          _ <-
            if (candidateParty == token.candidateParty) Right(())
            else
              authFailure(
                "provided party doesn't match party in token",
                s"$candidateParty != ${token.candidateParty}",
              )
          _ <-
            if (token.svcParty == svStore.key.svcParty) Right(())
            else authFailure("wrong svc party", s"${token.svcParty} != ${svStore.key.svcParty}")
        } yield (token.candidateParty, token.candidateName)
      )
  }

  private[sv] def isSvcMember(
      name: String,
      party: PartyId,
      svcRules: Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Boolean =
    svcRules.payload.members.asScala
      .get(party.toProtoPrimitive)
      .map(_.name == name)
      .getOrElse(false)

  private[sv] def isSvcMemberParty(
      party: PartyId,
      svcRules: Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Boolean = svcRules.payload.members.containsKey(party.toProtoPrimitive)

  private[sv] def isSvcMemberName(
      name: String,
      svcRules: Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Boolean = svcRules.payload.members.values.asScala.exists(_.name == name)

  private[sv] def isDevNet(
      svcRules: Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]
  ): Boolean = svcRules.payload.isDevNet

  val coinPackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String = cc.coin.Coin.COMPANION.TEMPLATE_ID.getPackageId

    // See `Compile / resourceGenerators` in build.sbt
    lazy val resourcePath: String = "dar/canton-coin-0.1.0.dar"
  }
  val coinV1TestPackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String = ccV1Test.coin.CoinRulesV1Test.COMPANION.TEMPLATE_ID.getPackageId

    lazy val resourcePath: String = "dar/canton-coin-0.1.1.dar"
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
  val directoryPackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String = cn.directory.DirectoryInstall.TEMPLATE_ID.getPackageId
    lazy val resourcePath: String = "dar/directory-service-0.1.0.dar"
  }
}
