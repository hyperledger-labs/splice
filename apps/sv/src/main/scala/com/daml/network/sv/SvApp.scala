package com.daml.network.sv

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Directives.*
import cats.implicits.catsSyntaxTuple2Semigroupal
import cats.syntax.either.*
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.User
import com.daml.network.admin.api.TraceContextDirectives.newTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.auth.{AuthConfig, HMACVerifier, RSAVerifier}
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.*
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.http.v0.commonAdmin.CommonAdminResource
import com.daml.network.http.v0.sv.SvResource
import com.daml.network.http.v0.svAdmin.SvAdminResource
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
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
import com.daml.network.sv.setup.{FoundingNodeInitializer, JoiningNodeInitializer}
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil}
import com.daml.network.util.{Contract, HasHealth, TemplateJsonDecoder, UploadablePackage}
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
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.circe.Json
import io.opentelemetry.api.trace.Tracer

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
) extends CNNodeBase[SvApp.State](
      config.ledgerApiUser,
      config.participantClient,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
    ) {

  private val cometBftConfig = config.cometBftConfig
    .filter(_.enabled)

  override def packages = super.packages ++ Seq("dar/svc-governance-0.1.0.dar")

  override def initializeNode(ledgerClient: CNLedgerClient): Future[SvApp.State] = {
    val participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      loggerFactory,
      retryProvider,
      clock,
    )
    val localDomainNode = config.localDomainNode
      .map(config =>
        new LocalDomainNode(
          new SequencerAdminConnection(
            config.sequencer.adminApi,
            loggerFactory,
            retryProvider,
            clock,
          ),
          new MediatorAdminConnection(
            config.mediator.adminApi,
            loggerFactory,
            retryProvider,
            clock,
          ),
          config.parameters
            .toStaticDomainParameters(CommunityCryptoConfig())
            .valueOr(err =>
              throw new IllegalArgumentException(s"Invalid domain parameters config: $err")
            ),
          config.sequencer.publicApi,
          loggerFactory,
          retryProvider,
        )
      )
    initialize(
      ledgerClient,
      participantAdminConnection,
      localDomainNode,
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
      ledgerClient: CNLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      localDomainNode: Option[LocalDomainNode],
  ): Future[SvApp.State] = {
    for {
      // It is possible that the participant left disconnected to domains due to party migration failure in the last SV startup.
      // reconnect all domains at the beginning of SV initialization just in case.
      _ <- participantAdminConnection.reconnectAllDomains()
      participantId <- retryProvider.getValueWithRetries(
        "Participant ID",
        participantAdminConnection.getParticipantId(),
        logger,
      )
      cometBftClient = newCometBftClient
      cometBftNode = (cometBftClient, cometBftConfig).mapN((client, config) =>
        new CometBftNode(client, config, loggerFactory)
      )

      // Ensure SVC party, SvcRules, CoinRules, Mediator, and Sequencer nodes are setup
      // -------------------------------------------------------------------------------

      case (
        (
          globalDomain,
          svcPartyHosting,
          svStore,
          svAutomation,
          svcStore,
          svcAutomation,
          svcRulesLock,
        ),
        foundingConfig,
      ) <-
      // We branch here on the type of onboarding config, as bootstrapping
      // a fresh collective is fundamentally different from joining an existing collective
      config.onboarding match {
        case Some(foundingConfig: SvOnboardingConfig.FoundCollective) =>
          val initializer = new FoundingNodeInitializer(
            config,
            foundingConfig,
            cometBftNode,
            darFilesToUploadDuringInit,
            loggerFactory,
            retryProvider,
            ledgerClient,
            participantAdminConnection,
            participantId,
            clock,
            storage,
            localDomainNode.getOrElse(
              sys.error("Founding node must always specify a domain config")
            ),
          )
          initializer.bootstrapCollective().map((_, Some(foundingConfig)))
        case Some(joiningConfig: SvOnboardingConfig.JoinWithKey) =>
          val initializer = new JoiningNodeInitializer(
            config,
            Some(joiningConfig),
            cometBftNode,
            darFilesToUploadDuringInit,
            loggerFactory,
            retryProvider,
            ledgerClient,
            participantAdminConnection,
            participantId,
            clock,
            storage,
            localDomainNode,
          )
          initializer.joinCollectiveAndOnboardNodes().map((_, None))
        case None =>
          val initializer = new JoiningNodeInitializer(
            config,
            None,
            cometBftNode,
            darFilesToUploadDuringInit,
            loggerFactory,
            retryProvider,
            ledgerClient,
            participantAdminConnection,
            participantId,
            clock,
            storage,
            localDomainNode,
          )
          initializer.joinCollectiveAndOnboardNodes().map((_, None))
      }

      // TODO(#5141) Remove the comment about DAR uploads.
      // We create the validator user only after the SVC party migration and DAR uploads have completed. This avoids two issues:
      // 1. The ValidatorLicense has both the SVC and the SV as a stakeholder.
      //    That can cause problems during the SVC party migration because the contract is imported there
      //    but could also be imported through the stream of the SV party. By only creating the validator user here
      //    we ensure that the party migration has been completed before the contract is created.
      // 2. Concurrent DAR uploads currently break Canton's topology state management.
      _ <- SvApp.createValidatorUser(svAutomation.connection, config, svStore.key.svParty)

      // Ensure Daml-level invariants for the SV
      // ----------------------------------------

      // At this point the complex setup of SVC party, sequencer, and mediators is done
      // What remains is setting up some SV-level Daml state.
      _ <- expectConfiguredValidatorOnboardings(
        svAutomation,
        globalDomain,
        clock,
      )
      _ <- approveConfiguredSvIdentities(svAutomation, globalDomain)
      isDevNet <- retryProvider.getValueWithRetriesNoPretty(
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

      // Start the servers for the SvApp's APIs
      // ---------------------------------------

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
        foundingConfig,
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
                    svStore.key.svParty,
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

  private val darFilesToUploadDuringInit: Seq[UploadablePackage] =
    Seq(
      SvApp.coinPackage,
      SvApp.svcGovernancePackage,
      SvApp.validatorLifecyclePackage,
      SvApp.directoryPackage,
    ).prependedAll(
      if (config.enableCoinRulesUpgrade)
        Seq(SvApp.coinV1TestPackage)
      else
        Seq.empty
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

  // TODO(#5364): move this and like functions into appropriate utility namespaces
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

  def createVoteRequest(
      requester: String,
      action: Json,
      reasonUrl: String,
      reasonDescription: String,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      globalDomain: DomainId,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
      templateJsonDecoder: TemplateJsonDecoder,
  ): Future[Either[String, Unit]] = {
    val decodedAction = templateJsonDecoder.decodeValue(
      ActionRequiringConfirmation.valueDecoder(),
      ActionRequiringConfirmation._packageId,
      "CN.SvcRules",
      "ActionRequiringConfirmation",
    )(action)
    svcStoreWithIngestion.store
      .lookupVoteRequestByThisSvAndActionWithOffset(decodedAction)
      .flatMap {
        case QueryResult(_, Some(vote)) =>
          Future.successful(
            Left(s"This vote request has already been created ${vote.contractId}.")
          )
        case QueryResult(offset, None) =>
          for {
            svcRules <- svcStoreWithIngestion.store.getSvcRules()
            reason = new Reason(reasonUrl, reasonDescription)
            request = new SvcRules_RequestVote(requester, decodedAction, reason)
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

  private def createValidatorUser(
      connection: CNLedgerConnection,
      config: SvAppBackendConfig,
      svParty: PartyId,
  ): Future[PartyId] = {
    // We share the SV party between the validator user and the SV user. Therefore, we allocate the validator user here with the SV
    // party as the primary one. We allocate the user here and don't just tweak the primary party of an externally allocated user.
    // That ensures the validator app won't try to allocate its own primary party because it waits first for the user to be created
    // and then checks if it has a primary party already.
    connection.createUserWithPrimaryParty(
      config.validatorLedgerApiUser,
      svParty,
      Seq(User.Right.ParticipantAdmin.INSTANCE),
    )
  }

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
