package com.daml.network.sv

import cats.data.OptionT
import cats.implicits.{catsSyntaxTuple2Semigroupal, catsSyntaxTuple6Semigroupal}
import cats.instances.future.*
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.User
import com.daml.network.admin.api.TraceContextDirectives.withTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.auth.{AdminAuthExtractor, AuthConfig, HMACVerifier, RSAVerifier}
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.*
import com.daml.network.http.v0.external.common_admin.CommonAdminResource
import com.daml.network.http.v0.sv.SvResource
import com.daml.network.http.v0.sv_admin.SvAdminResource
import com.daml.network.migration.AcsExporter
import com.daml.network.setup.{NodeInitializer, ParticipantInitializer}
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.admin.http.{HttpSvAdminHandler, HttpSvHandler}
import com.daml.network.sv.automation.{
  LeaderBasedAutomationService,
  SvSvAutomationService,
  SvSvcAutomationService,
}
import com.daml.network.sv.cometbft.{
  CometBftClient,
  CometBftConnectionConfig,
  CometBftHttpRpcClient,
  CometBftNode,
}
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.metrics.SvAppMetrics
import com.daml.network.sv.migration.{DomainDataSnapshotGenerator, DomainNodeIdentities}
import com.daml.network.sv.onboarding.domainmigration.DomainMigrationInitializer
import com.daml.network.sv.onboarding.founder.FoundingNodeInitializer
import com.daml.network.sv.onboarding.joining.JoiningNodeInitializer
import com.daml.network.sv.onboarding.sponsor.SvcPartyMigration
import com.daml.network.sv.store.{SvSvcStore, SvSvStore}
import com.daml.network.sv.util.SvOnboardingToken
import com.daml.network.util.{
  BackupDump,
  Contract,
  HasHealth,
  TemplateJsonDecoder,
  UploadablePackage,
}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{
  CommunityCryptoConfig,
  CommunityCryptoProvider,
  NonNegativeFiniteDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
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
import io.circe.Json
import io.circe.syntax.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.cors
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.server.Directives.*

import java.nio.file.Paths
import scala.concurrent.{blocking, ExecutionContext, ExecutionContextExecutor, Future}
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
    metrics: SvAppMetrics,
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
      metrics,
    ) {

  private val cometBftConfig = config.cometBftConfig
    .filter(_.enabled)

  override def packages: Seq[DarResource] =
    super.packages ++ DarResources.svcGovernance.all ++ DarResources.validatorLifecycle.all ++ DarResources.cantonNameService.all

  override def preInitializeBeforeLedgerConnection(): Future[Unit] = {
    val participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      coinAppParameters.loggingConfig.api,
      loggerFactory,
      metrics.grpcClientMetrics,
      retryProvider,
    )
    (for {
      _ <- appInitStep("ensure beneficiary weights are <= 100.0") {
        if (config.extraBeneficiaries.values.sum > BigDecimal(100)) {
          sys.error(
            s"Beneficiaries' weight percentage sum exceeds 100. Beneficiaries: ${config.extraBeneficiaries}"
          )
        } else Future.unit
      }
      _ <-
        appInitStep("Ensure participant is initialized with expected id") {
          config.onboarding match {
            case Some(SvOnboardingConfig.DomainMigration(_, dumpFilePath)) =>
              logger.info(
                "We're restoring from a migration dump, ensuring participant is initialized"
              )
              val participantInitializer = new NodeInitializer(
                participantAdminConnection,
                retryProvider,
                loggerFactory,
              )
              participantInitializer.initializeAndWait(
                DomainMigrationInitializer
                  .loadDomainMigrationDump(dumpFilePath)
                  .nodeIdentities
                  .participant
              )

            case _ =>
              ParticipantInitializer.ensureParticipantInitializedWithExpectedId(
                participantAdminConnection,
                config.participantBootstrappingDump,
                loggerFactory,
                retryProvider,
              )
          }
        }
    } yield ()).andThen { case _ => participantAdminConnection.close() }
  }

  override def initializeNode(
      ledgerClient: CNLedgerClient
  ): Future[SvApp.State] = {
    val participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      coinAppParameters.loggingConfig.api,
      loggerFactory,
      metrics.grpcClientMetrics,
      retryProvider,
    )

    val localDomainNode = config.localDomainNode
      .map(config =>
        new LocalDomainNode(
          participantAdminConnection,
          new SequencerAdminConnection(
            config.sequencer.adminApi,
            coinAppParameters.loggingConfig.api,
            loggerFactory,
            metrics.grpcClientMetrics,
            retryProvider,
          ),
          new MediatorAdminConnection(
            config.mediator.adminApi,
            coinAppParameters.loggingConfig.api,
            loggerFactory,
            metrics.grpcClientMetrics,
            retryProvider,
          ),
          config.parameters
            .toStaticDomainParameters(
              CommunityCryptoConfig(provider = CommunityCryptoProvider.Tink)
            )
            .valueOr(err =>
              throw new IllegalArgumentException(s"Invalid domain parameters config: $err")
            ),
          config.sequencer.internalApi,
          config.sequencer.externalPublicApiUrl,
          config.sequencer.sequencerAvailabilityDelay.asJava,
          config.sequencer.pruning,
          loggerFactory,
          retryProvider,
        )
      )
    initialize(
      participantAdminConnection,
      ledgerClient,
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
      participantAdminConnection: ParticipantAdminConnection,
      ledgerClient: CNLedgerClient,
      localDomainNode: Option[LocalDomainNode],
  ): Future[SvApp.State] = {
    val cometBftClient = newCometBftClient
    val cometBftNode = (cometBftClient, cometBftConfig).mapN((client, config) =>
      new CometBftNode(client, config, loggerFactory)
    )

    for {
      (_, participantId) <- (
        // It is possible that the participant left disconnected to domains due to party migration failure in the last SV startup.
        // reconnect all domains at the beginning of SV initialization just in case.
        appInitStep("Reconnect all domains") {
          retryProvider.retry(
            RetryFor.WaitingOnInitDependency,
            "Reconnect all domains",
            participantAdminConnection.reconnectAllDomains(),
            logger,
          )
        },
        appInitStep("Get participant ID") {
          retryProvider.getValueWithRetries(
            RetryFor.WaitingOnInitDependency,
            "Participant ID",
            participantAdminConnection.getParticipantId(),
            logger,
          )
        },
      ).tupled
      newJoiningNodeInitializer = (joiningConfig: Option[SvOnboardingConfig.JoinWithKey]) =>
        new JoiningNodeInitializer(
          localDomainNode,
          joiningConfig,
          participantId,
          darFilesToUploadDuringInit,
          config,
          cometBftNode,
          ledgerClient,
          participantAdminConnection,
          clock,
          storage,
          loggerFactory,
          retryProvider,
        )
      // Ensure SVC party, SvcRules, CoinRules, Mediator, and Sequencer nodes are setup
      // -------------------------------------------------------------------------------
      case (
        globalDomain,
        svcPartyHosting,
        svStore,
        svAutomation,
        svcStore,
        svcAutomation,
      ) <-
      // We branch here on the type of onboarding config, as bootstrapping
      // a fresh collective is fundamentally different from joining an existing collective
      config.onboarding match {
        case Some(foundingConfig: SvOnboardingConfig.FoundCollective) =>
          appInitStep("FoundingNodeInitializer founding collective") {
            val initializer = new FoundingNodeInitializer(
              localDomainNode.getOrElse(
                sys.error("Founding node must always specify a domain config")
              ),
              foundingConfig,
              darFilesToUploadDuringInit,
              participantId,
              config,
              cometBftNode,
              ledgerClient,
              participantAdminConnection,
              clock,
              storage,
              retryProvider,
              loggerFactory,
            )
            initializer.bootstrapCollective()
          }
        case Some(joiningConfig: SvOnboardingConfig.JoinWithKey) =>
          appInitStep("JoiningNodeInitializer joining collective with key") {
            val initializer = newJoiningNodeInitializer(Some(joiningConfig))
            initializer.joinCollectiveAndOnboardNodes()
          }
        case Some(domainMigrationConfig: SvOnboardingConfig.DomainMigration) =>
          appInitStep("DomainMigrationInitializer initializing node from dump") {
            val joiningNodeInitializer = newJoiningNodeInitializer(None)
            new DomainMigrationInitializer(
              localDomainNode.getOrElse(
                sys.error("It must always specify a domain config for Domain Migration")
              ),
              domainMigrationConfig,
              participantId,
              config,
              cometBftNode,
              ledgerClient,
              participantAdminConnection,
              clock,
              storage,
              loggerFactory,
              retryProvider,
              joiningNodeInitializer,
            ).migrateDomain()
          }
        case None =>
          appInitStep("JoiningNodeInitializer joining collective") {
            val initializer = newJoiningNodeInitializer(None)
            initializer.joinCollectiveAndOnboardNodes()
          }
      }

      (_, _, isDevNet, _, _, _) <- (
        // TODO(#5141) Remove the comment about DAR uploads.
        // We create the validator user only after the SVC party migration and DAR uploads have completed. This avoids two issues:
        // 1. The ValidatorLicense has both the SVC and the SV as a stakeholder.
        //    That can cause problems during the SVC party migration because the contract is imported there
        //    but could also be imported through the stream of the SV party. By only creating the validator user here
        //    we ensure that the party migration has been completed before the contract is created.
        // 2. Concurrent DAR uploads currently break Canton's topology state management.
        appInitStep("Initialize validator") {
          SvApp.initializeValidator(svcAutomation, config, retryProvider, logger)
        },
        // Ensure Daml-level invariants for the SV
        // ----------------------------------------

        // At this point the complex setup of SVC party, sequencer, and mediators is done
        // What remains is setting up some SV-level Daml state.
        appInitStep("Expect configured validator onboardings") {
          expectConfiguredValidatorOnboardings(
            svAutomation,
            globalDomain,
            clock,
          )
        },
        appInitStep("Get CoinRules to determine if we are in a DevNet") {
          retryProvider.getValueWithRetriesNoPretty(
            RetryFor.WaitingOnInitDependency,
            "get CoinRules to determine if we are in a DevNet",
            svcStore.getCoinRules().map(coinRules => coinRules.payload.isDevNet),
            logger,
          )
        },
        appInitStep("Ensure coin price has a vote") {
          config.initialCoinPriceVote
            .map(
              ensureCoinPriceVoteHasCoinPrice(
                _,
                svcAutomation,
                logger,
              )
            )
            .getOrElse(Future.unit)
        },
        appInitStep("Dump identities") {
          SvApp.backupNodeIdentities(
            config,
            localDomainNode,
            svcStore,
            participantAdminConnection,
            clock,
            logger,
            loggerFactory,
          )
        },
        appInitStep("Wait until configured onboarding contracts have been created") {
          waitUntilConfiguredOnboardingContractsHaveBeenCreated(svStore)
        },
      ).tupled

      // We're registering the trafficBalanceService on the LedgerClient after all the SV onboarding steps
      // because we do not want the onboarding to be throttled by the balance check.
      trafficBalanceService = newTrafficBalanceService(participantAdminConnection)
      _ = ledgerClient.registerTrafficBalanceService(trafficBalanceService)

      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl) => new RSAVerifier(audience, jwksUrl)
      }

      // Start the servers for the SvApp's APIs
      // ---------------------------------------

      handler = new HttpSvHandler(
        config.ledgerApiUser,
        svAutomation,
        svcAutomation,
        isDevNet,
        config,
        clock,
        participantAdminConnection,
        localDomainNode,
        retryProvider,
        new SvcPartyMigration(
          svAutomation,
          svcAutomation,
          participantAdminConnection,
          retryProvider,
          svcPartyHosting,
          loggerFactory,
        ),
        cometBftClient,
        loggerFactory,
      )

      adminHandler = new HttpSvAdminHandler(
        config,
        config.domainMigrationDumpPath,
        svAutomation,
        svcAutomation,
        cometBftClient,
        localDomainNode,
        participantAdminConnection,
        new DomainDataSnapshotGenerator(
          participantAdminConnection,
          svcStore,
          new AcsExporter(participantAdminConnection, retryProvider, loggerFactory),
        ),
        clock,
        retryProvider,
        loggerFactory,
      )

      commonAdminHandler = new HttpAdminHandler(
        status
          .map(CNNodeStatus.fromNodeStatus)
          .map(NodeStatus.Success(_)),
        loggerFactory,
      )

      routes = cors(
        CorsSettings(ac)
          .withAllowedMethods(
            List(
              HttpMethods.DELETE,
              HttpMethods.GET,
              HttpMethods.POST,
              HttpMethods.PUT,
              HttpMethods.HEAD,
              HttpMethods.OPTIONS,
            )
          )
          .withExposedHeaders(Seq("traceparent"))
      ) {
        withTraceContext { implicit traceContext =>
          requestLogger(traceContext) {
            HttpErrorHandler(loggerFactory)(traceContext) {
              concat(
                SvResource.routes(
                  handler,
                  _ => provide(traceContext),
                ),
                SvAdminResource.routes(
                  adminHandler,
                  AdminAuthExtractor(
                    verifier,
                    svStore.key.svParty,
                    svAutomation.connection,
                    loggerFactory,
                    "canton network sv admin realm",
                  ),
                ),
                pathPrefix("api" / "sv")(
                  CommonAdminResource.routes(commonAdminHandler, _ => provide(traceContext))
                ),
              )
            }
          }
        }

      }
      binding <- appInitStep(s"Start http server on ${config.adminApi.clientConfig}") {
        Http()
          .newServerAt(
            config.adminApi.clientConfig.address,
            config.adminApi.clientConfig.port.unwrap,
          )
          .bind(
            routes
          )
      }
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
        httpClient,
        templateDecoder,
      )
    }
  }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  protected[this] override def automationServices(st: SvApp.State) =
    Seq(LeaderBasedAutomationService, st.svAutomation, st.svcAutomation)

  private val darFilesToUploadDuringInit: Seq[UploadablePackage] =
    Seq(
      SvApp.coinPackage,
      SvApp.svcGovernancePackage,
      SvApp.validatorLifecyclePackage,
    )

  private def newTrafficBalanceService(participantAdminConnection: ParticipantAdminConnection) = {
    TrafficBalanceService(
      _ => Future.successful(Some(config.domains.global.trafficReservedForTopups)),
      participantAdminConnection,
      clock,
      config.domains.global.trafficBalanceCacheTimeToLive,
      loggerFactory,
    )
  }

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

  private def waitUntilConfiguredOnboardingContractsHaveBeenCreated(
      store: SvSvStore
  ): Future[Unit] = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "Onboarding contracts have been created", {
        val expectedValidatorOnboardingSecrets = config.expectedValidatorOnboardings.map(_.secret)
        for {
          createdValidatorOnboardingSecrets <- expectedValidatorOnboardingSecrets.traverse {
            secret =>
              OptionT(store.lookupValidatorOnboardingBySecret(secret))
                .map(_ => ())
                .orElse(OptionT(store.lookupUsedSecret(secret)).map(_ => ()))
                .value
          }
          missingOnboardingSecrets = createdValidatorOnboardingSecrets.zipWithIndex.filter(
            _._1.isEmpty
          )
          _ <-
            if (missingOnboardingSecrets.isEmpty) {
              Future.unit
            } else {
              Future.failed(
                Status.NOT_FOUND
                  .withDescription(
                    s"Missing ${missingOnboardingSecrets.size}/${expectedValidatorOnboardingSecrets.size} onboarding secrets"
                  )
                  .asRuntimeException()
              )
            }
        } yield ()
      },
      logger,
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
    retryProvider.retry(
      RetryFor.WaitingOnInitDependency,
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

  private def ensureCoinPriceVoteHasCoinPrice(
      defaultCoinPriceVote: BigDecimal,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      logger: TracedLogger,
  ): Future[Either[String, Unit]] =
    svcStoreWithIngestion.store.lookupCoinPriceVoteByThisSv().flatMap {
      case Some(vote) if vote.payload.coinPrice.toScala.isDefined =>
        logger.info(s"A coin price vote with a defined coin price already exists")
        Future.successful(Right(()))
      case _ =>
        retryProvider.retry(
          RetryFor.WaitingOnInitDependency,
          "Update coin price vote to configured initial coin price vote",
          SvApp
            .updateCoinPriceVote(
              defaultCoinPriceVote,
              svcStoreWithIngestion,
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
      httpClient: HttpRequest => Future[HttpResponse],
      decoder: TemplateJsonDecoder,
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
          timeouts.shutdownNetwork,
        ),
        SyncCloseable(
          s"Domain connections",
          localDomainNode.foreach(_.close()),
        ),
        SyncCloseable(
          s"Participant Admin connection",
          participantAdminConnection.close(),
        ),
        SyncCloseable("sv automation", svAutomation.close()),
        SyncCloseable("svc automation", svcAutomation.close()),
        SyncCloseable("sv store", svStore.close()),
        SyncCloseable("svc store", svcStore.close()),
        SyncCloseable("storage", storage.close()),
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
    ).create()
    for {
      res <- svStore.lookupUsedSecretWithOffset(secret).flatMap {
        case QueryResult(_, Some(usedSecret)) =>
          val validator = usedSecret.payload.validator
          Future.successful(
            Left(s"This secret has already been used before, for onboarding validator $validator")
          )
        case QueryResult(offset, None) =>
          svStore.lookupValidatorOnboardingBySecretWithOffset(secret).flatMap {
            case QueryResult(_, Some(_)) =>
              Future.successful(
                Left("A validator onboarding contract with this secret already exists.")
              )
            case QueryResult(_, None) =>
              for {
                _ <- svStoreWithIngestion.connection
                  .submit(actAs = Seq(svParty), readAs = Seq.empty, update = validatorOnboarding)
                  .withDedup(
                    commandId = CNLedgerConnection
                      .CommandId(
                        "com.daml.network.sv.expectValidatorOnboarding",
                        Seq(svParty),
                        secret, // not a leak as this gets hashed before it's used
                      ),
                    deduplicationOffset = offset,
                  )
                  .withDomainId(domainId = globalDomain)
                  .yieldUnit()
              } yield {
                logger.info("Created new ValidatorOnboarding contract.")
                Right(())
              }
          }
      }
    } yield res
  }

  def updateCoinPriceVote(
      desiredCoinPrice: BigDecimal,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
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
          cmd = svcRules.exercise(
            _.exerciseSvcRules_UpdateCoinPriceVote(
              svcStore.key.svParty.toProtoPrimitive,
              vote.contractId,
              desiredCoinPrice.bigDecimal,
            )
          )
          _ <- svcStoreWithIngestion.connection
            .submit(
              actAs = Seq(svcStore.key.svParty),
              readAs = Seq(svcStore.key.svcParty),
              update = cmd,
            )
            .noDedup
            .yieldUnit()
        } yield Right(())
      case None =>
        Future.successful(
          Left(
            s"No cc price vote contract found for this SV. It is not expected as it should be created when the SV was added to SVC,"
          )
        )
    }
  }

  def getElectionRequest(
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore]
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Seq[Contract[ElectionRequest.ContractId, ElectionRequest]]] = {
    val store = svcStoreWithIngestion.store
    for {
      svcRules <- store.getSvcRules()
    } yield store.listElectionRequests(svcRules)
  }.flatten

  def createElectionRequest(
      requester: String,
      ranking: Seq[String],
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Either[String, Unit]] = {
    val store = svcStoreWithIngestion.store
    for {
      svcRules <- store.getSvcRules()
      queryResult <- store
        .lookupElectionRequestByRequesterWithOffset(
          PartyId.tryFromProtoPrimitive(requester),
          svcRules.payload.epoch,
        )
      result <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            Left(
              s"already voted in an election for epoch ${svcRules.payload.epoch} to replace inactive leader ${svcRules.payload.leader}"
            )
          )
        case QueryResult(offset, None) =>
          val self = requester
          val cmd = svcRules.exercise(
            _.exerciseSvcRules_RequestElection(
              self,
              new cn.svcrules.electionrequestreason.ERR_LeaderUnavailable(
                com.daml.ledger.javaapi.data.Unit.getInstance()
              ),
              ranking.asJava,
            )
          )
          for {
            _ <- svcStoreWithIngestion.connection
              .submit(
                actAs = Seq(store.key.svParty),
                readAs = Seq(store.key.svcParty),
                cmd,
              )
              .withDedup(
                commandId = CNLedgerConnection.CommandId(
                  "com.daml.network.sv.requestElection",
                  Seq(
                    store.key.svParty,
                    store.key.svcParty,
                  ),
                  svcRules.payload.epoch.toString,
                ),
                deduplicationOffset = offset,
              )
              .yieldUnit()
          } yield Right(())
      }
    } yield result
  }

  def createVoteRequest(
      requester: String,
      action: Json,
      reasonUrl: String,
      reasonDescription: String,
      expiration: Json,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
      templateJsonDecoder: TemplateJsonDecoder,
  ): Future[Either[String, Unit]] = {
    val decodedExpiration = templateJsonDecoder.decodeValue(
      RelTime.valueDecoder(),
      RelTime._packageId,
      "DA.Time.Types",
      "RelTime",
    )(expiration)
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
            request = new SvcRules_RequestVote(
              requester,
              decodedAction,
              reason,
              java.util.Optional.of(decodedExpiration),
            )
            cmd = svcRules.exercise(_.exerciseSvcRules_RequestVote(request))
            _ <- svcStoreWithIngestion.connection
              .submit(
                actAs = Seq(svcStoreWithIngestion.store.key.svParty),
                readAs = Seq(svcStoreWithIngestion.store.key.svcParty),
                cmd,
              )
              .withDedup(
                commandId = CNLedgerConnection.CommandId(
                  "com.daml.network.sv.requestVote",
                  Seq(
                    svcStoreWithIngestion.store.key.svcParty,
                    svcStoreWithIngestion.store.key.svParty,
                  ),
                  action.toString,
                ),
                deduplicationOffset = offset,
              )
              .yieldUnit()
          } yield Right(())
      }
  }

  def castVote(
      trackingCid: cn.svcrules.VoteRequest.ContractId,
      isAccepted: Boolean,
      reasonUrl: String,
      reasonDescription: String,
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Either[String, cn.svcrules.VoteRequest.ContractId]] = {
    svcStoreWithIngestion.store
      .lookupVoteByThisSvAndVoteRequestWithOffset(trackingCid)
      .flatMap { case QueryResult(_, _) =>
        for {
          svcRules <- svcStoreWithIngestion.store.getSvcRules()
          res <- retryProvider.retryForClientCalls(
            "castVote",
            for {
              resolvedVoteRequest <- svcStoreWithIngestion.store.getVoteRequest(trackingCid)
              resolvedCid = resolvedVoteRequest.contractId
              reason = new Reason(reasonUrl, reasonDescription)
              cmd = svcRules.exercise(
                _.exerciseSvcRules_CastVote(
                  resolvedCid,
                  new Vote(
                    svcStoreWithIngestion.store.key.svParty.toProtoPrimitive,
                    isAccepted,
                    reason,
                  ),
                )
              )
              res <- svcStoreWithIngestion.connection
                .submit(
                  actAs = Seq(svcStoreWithIngestion.store.key.svParty),
                  readAs = Seq(svcStoreWithIngestion.store.key.svcParty),
                  update = cmd,
                )
                .noDedup
                .yieldResult()
            } yield res,
            logger,
          )
        } yield Right(res.exerciseResult)
      }
  }

  private[sv] def isApprovedSvIdentity(
      candidateName: String,
      candidateParty: PartyId,
      rawToken: String,
      config: SvAppBackendConfig,
      svStore: SvSvStore,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext
  ): Either[String, (PartyId, String, Long)] = {

    // We want to make sure that:
    // 1. we log warnings whenever an auth check fails
    // 2. details about the fails are not communicated to requesters
    def authFailure(reason: String, details: String): Left[String, (PartyId, String)] = {
      logger.warn(s"SV candidate authentication failure: $reason ($details)")
      Left(reason)
    }

    val approvedSvO = config.approvedSvIdentities.find(_.name == candidateName)

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
        if (token.candidateKey == approvedSv.publicKey) Right(())
        else
          authFailure(
            "candidate key doesn't match approved key",
            s"${token.candidateKey} != ${approvedSv.publicKey}",
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
        else authFailure("wrong SVC party", s"${token.svcParty} != ${svStore.key.svcParty}")
    } yield (token.candidateParty, token.candidateName, approvedSv.rewardWeightBps)
  }

  private[sv] def isSvcMember(
      name: String,
      party: PartyId,
      svcRules: Contract.Has[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Boolean =
    svcRules.payload.members.asScala
      .get(party.toProtoPrimitive)
      .exists(_.name == name)

  private[sv] def validateSvNamespace(
      candidateParty: PartyId,
      candidateParticipantId: ParticipantId,
  ): Boolean = candidateParty.uid.namespace == candidateParticipantId.uid.namespace

  private[sv] def validateCandidateSv(
      candidateParty: PartyId,
      candidateName: String,
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
  ): Either[Status, Unit] = {
    for {
      _ <- Either.cond(
        !SvApp.isSvcMemberParty(candidateParty, svcRules),
        (),
        Status.ALREADY_EXISTS.withDescription(
          s"An SV with party ID $candidateParty already exists."
        ),
      )
      _ <-
        if (!SvApp.isDevNet(svcRules)) {
          SvApp
            .getSvcPartyFromName(candidateName, svcRules)
            .toLeft(())
            .leftMap(partyId =>
              Status.ALREADY_EXISTS
                .withDescription(
                  s"Candidate SV $candidateParty cannot use name `$candidateName` because it's used by SV with party ID $partyId."
                )
            )
        } else Right(())
    } yield ()
  }

  private[sv] def isSvcMemberParty(
      party: PartyId,
      svcRules: Contract.Has[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Boolean = svcRules.payload.members.containsKey(party.toProtoPrimitive)

  private[sv] def isSvcMemberName(
      name: String,
      svcRules: Contract.Has[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Boolean = getSvcPartyFromName(name, svcRules).isDefined

  private[sv] def getSvcPartyFromName(
      name: String,
      svcRules: Contract.Has[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Option[String] = {
    svcRules.payload.members.asScala.collectFirst {
      case (partyId, memberInfo) if memberInfo.name == name => partyId
    }
  }

  private[sv] def isDevNet(
      svcRules: Contract.Has[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]
  ): Boolean = svcRules.payload.isDevNet

  private def initializeValidator(
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      config: SvAppBackendConfig,
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Unit] = {
    val store = svcStoreWithIngestion.store
    val svParty = store.key.svParty
    logger.debug("Receiving or creating validator license for SV party")
    for {
      _ <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "Create validator license for SV party",
        for {
          svcRules <- store.getSvcRules()
          validatorLicense <- store.lookupValidatorLicenseWithOffset(
            svParty
          )
          _ <- validatorLicense match {
            case QueryResult(_, Some(_)) =>
              logger.info("Validator license for SV party already exists")
              Future.unit
            case QueryResult(offset, None) =>
              logger.debug("Trying to create validator license for SV party")
              val cmd = svcRules.exercise(
                _.exerciseSvcRules_OnboardValidator(
                  svParty.toProtoPrimitive,
                  svParty.toProtoPrimitive,
                )
              )
              svcStoreWithIngestion.connection
                .submit(
                  actAs = Seq(svParty),
                  readAs = Seq(store.key.svcParty),
                  cmd,
                )
                .withDedup(
                  commandId = CNLedgerConnection.CommandId(
                    "com.daml.network.sv.createSvValidatorLicense",
                    Seq(
                      store.key.svcParty,
                      svParty,
                    ),
                    svParty.toProtoPrimitive,
                  ),
                  deduplicationOffset = offset,
                )
                .yieldUnit()
                .map { _ =>
                  logger.info("Created validator license for SV party")
                }
          }
        } yield (),
        logger,
      )
      // We share the SV party between the validator user and the SV user. Therefore, we allocate the validator user here with the SV
      // party as the primary one. We allocate the user here and don't just tweak the primary party of an externally allocated user.
      // That ensures the validator app won't try to allocate its own primary party because it waits first for the user to be created
      // and then checks if it has a primary party already.
      _ <- svcStoreWithIngestion.connection.createUserWithPrimaryParty(
        config.validatorLedgerApiUser,
        svParty,
        Seq(User.Right.ParticipantAdmin.INSTANCE),
      )
    } yield ()
  }

  private def backupNodeIdentities(
      config: SvAppBackendConfig,
      localDomainNode: Option[LocalDomainNode],
      svcStore: SvSvcStore,
      participantAdminConnection: ParticipantAdminConnection,
      clock: Clock,
      logger: TracedLogger,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Unit] = {
    config.identitiesDump.fold(Future.successful(()))(backupConfig => {
      val now = clock.now.toInstant
      val filename = Paths.get(
        s"sv_identities_${now}.json"
      )
      logger.debug(
        s"Attempting to write node identities to ${backupConfig.locationDescription} at path: $filename"
      )
      for {
        identities <- DomainNodeIdentities.getDomainNodeIdentities(
          participantAdminConnection,
          localDomainNode.getOrElse(
            sys.error("Cannot dump identities with no localDomainNode")
          ),
          svcStore,
          config.domains.global.alias,
          loggerFactory,
        )
        _ <- Future {
          blocking {
            BackupDump.write(
              backupConfig,
              filename,
              identities.toHttp().asJson.noSpaces,
              loggerFactory,
            )
          }
        }
      } yield ()
    })
  }

  val coinPackage: UploadablePackage =
    UploadablePackage.fromResource(DarResources.cantonCoin.bootstrap)
  val svcGovernancePackage: UploadablePackage =
    UploadablePackage.fromResource(DarResources.svcGovernance.bootstrap)
  val validatorLifecyclePackage: UploadablePackage =
    UploadablePackage.fromResource(DarResources.validatorLifecycle.bootstrap)
}
