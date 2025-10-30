// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import cats.data.OptionT
import cats.implicits.catsSyntaxTuple6Semigroupal
import cats.instances.future.*
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.User
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{
  CryptoConfig,
  CryptoProvider,
  NonNegativeFiniteDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.version.ProtocolVersion
import io.circe.Json
import io.circe.syntax.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.cors
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.server.Directive
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.lfdecentralizedtrust.splice.admin.api.TraceContextDirectives.withTraceContext
import org.lfdecentralizedtrust.splice.admin.http.{AdminRoutes, HttpErrorHandler}
import org.lfdecentralizedtrust.splice.auth.{
  AdminAuthExtractor,
  AuthConfig,
  HMACVerifier,
  RSAVerifier,
}
import org.lfdecentralizedtrust.splice.automation.{
  DomainParamsAutomationService,
  DomainTimeAutomationService,
}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.*
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection.INITIAL_ROUND_USER_METADATA_KEY
import org.lfdecentralizedtrust.splice.http.v0.sv.SvResource
import org.lfdecentralizedtrust.splice.http.v0.sv_admin.SvAdminResource
import org.lfdecentralizedtrust.splice.http.{HttpClient, HttpRateLimiter}
import org.lfdecentralizedtrust.splice.migration.AcsExporter
import org.lfdecentralizedtrust.splice.setup.{NodeInitializer, ParticipantInitializer}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.{AppStoreWithIngestion, UpdateHistory}
import org.lfdecentralizedtrust.splice.sv.admin.http.{HttpSvAdminHandler, HttpSvHandler}
import org.lfdecentralizedtrust.splice.sv.automation.{
  DsoDelegateBasedAutomationService,
  SvDsoAutomationService,
  SvSvAutomationService,
}
import org.lfdecentralizedtrust.splice.sv.cometbft.{
  CometBftClient,
  CometBftConnectionConfig,
  CometBftHttpRpcClient,
  CometBftNode,
}
import org.lfdecentralizedtrust.splice.sv.config.{
  SvAppBackendConfig,
  SvCantonIdentifierConfig,
  SvOnboardingConfig,
}
import org.lfdecentralizedtrust.splice.sv.metrics.SvAppMetrics
import org.lfdecentralizedtrust.splice.sv.migration.DomainDataSnapshotGenerator
import org.lfdecentralizedtrust.splice.sv.onboarding.domainmigration.DomainMigrationInitializer
import org.lfdecentralizedtrust.splice.sv.onboarding.joining.JoiningNodeInitializer
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.DsoPartyMigration
import org.lfdecentralizedtrust.splice.sv.onboarding.sv1.SV1Initializer
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.util.{
  JsonOnboardingSecret,
  SvOnboardingToken,
  SvUtil,
  ValidatorOnboardingSecret,
}
import org.lfdecentralizedtrust.splice.util.{Contract, HasHealth, TemplateJsonDecoder}

import java.time.Instant
import java.util.Optional
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SvApp(
    override val name: InstanceName,
    val config: SvAppBackendConfig,
    val amuletAppParameters: SharedSpliceAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    metrics: SvAppMetrics,
    adminRoutes: AdminRoutes,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends NodeBase[SvApp.State](
      config.ledgerApiUser,
      config.participantClient,
      amuletAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      metrics,
    ) {

  private val cometBftConfig = config.cometBftConfig
    .filter(_.enabled)

  override def packagesForJsonDecoding: Seq[DarResource] =
    super.packagesForJsonDecoding ++ DarResources.dsoGovernance.all ++ DarResources.validatorLifecycle.all ++ DarResources.amuletNameService.all

  override def preInitializeBeforeLedgerConnection()(implicit tc: TraceContext): Future[Unit] = {
    val participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      amuletAppParameters.loggingConfig.api,
      loggerFactory,
      metrics.grpcClientMetrics,
      retryProvider,
    )
    (for {
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
              participantInitializer.initializeFromDumpAndWait(
                DomainMigrationInitializer
                  .loadDomainMigrationDump(dumpFilePath)
                  .nodeIdentities
                  .participant
              )

            case _ =>
              UpdateHistory.getHighestKnownMigrationId(storage).flatMap {
                case Some(migrationId) if migrationId < config.domainMigrationId =>
                  throw Status.INVALID_ARGUMENT
                    .withDescription(
                      s"Migration ID was incremented (to ${config.domainMigrationId}) but no migration dump for restoring from was specified."
                    )
                    .asRuntimeException()
                case _ =>
                  logger.info(
                    "Ensuring participant is initialized"
                  )
                  val cantonIdentifierConfig = config.cantonIdentifierConfig.getOrElse(
                    SvCantonIdentifierConfig.default(config)
                  )
                  ParticipantInitializer.ensureParticipantInitializedWithExpectedId(
                    cantonIdentifierConfig.participant,
                    participantAdminConnection,
                    config.participantBootstrappingDump,
                    loggerFactory,
                    retryProvider,
                  )
              }
          }
        }
    } yield ()).andThen { case _ => participantAdminConnection.close() }
  }

  override def initializeNode(
      ledgerClient: SpliceLedgerClient
  )(implicit tc: TraceContext): Future[SvApp.State] = {
    val participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      amuletAppParameters.loggingConfig.api,
      loggerFactory,
      metrics.grpcClientMetrics,
      retryProvider,
    )

    val localSynchronizerNode = config.localSynchronizerNode
      .map(svSynchronizerConfig =>
        new LocalSynchronizerNode(
          participantAdminConnection,
          new SequencerAdminConnection(
            svSynchronizerConfig.sequencer.adminApi,
            amuletAppParameters.loggingConfig.api,
            loggerFactory,
            metrics.grpcClientMetrics,
            retryProvider,
          ),
          new MediatorAdminConnection(
            svSynchronizerConfig.mediator.adminApi,
            amuletAppParameters.loggingConfig.api,
            loggerFactory,
            metrics.grpcClientMetrics,
            retryProvider,
          ),
          svSynchronizerConfig.parameters
            .toStaticSynchronizerParameters(
              CryptoConfig(provider = CryptoProvider.Jce),
              ProtocolVersion.v33,
            )
            .valueOr(err =>
              throw new IllegalArgumentException(s"Invalid domain parameters config: $err")
            ),
          svSynchronizerConfig.sequencer.internalApi,
          svSynchronizerConfig.sequencer.externalPublicApiUrl,
          svSynchronizerConfig.sequencer.sequencerAvailabilityDelay.asJava,
          svSynchronizerConfig.sequencer.pruning,
          svSynchronizerConfig.mediator.sequencerRequestAmplification,
          loggerFactory,
          retryProvider,
          SequencerConfig.fromConfig(
            svSynchronizerConfig.sequencer,
            cometBftConfig,
          ),
        )
      )
    initialize(
      participantAdminConnection,
      ledgerClient,
      localSynchronizerNode,
    )
      .recoverWith { case err =>
        // TODO(#879) Replace this by a more general solution for closing resources on
        // init failures.
        participantAdminConnection.close()
        localSynchronizerNode.foreach(_.close())
        Future.failed(err)
      }
  }

  private def initialize(
      participantAdminConnection: ParticipantAdminConnection,
      ledgerClient: SpliceLedgerClient,
      localSynchronizerNode: Option[LocalSynchronizerNode],
  )(implicit tc: TraceContext): Future[SvApp.State] = {
    val cometBftClient = newCometBftClient

    for {
      participantId <- appInitStep("Get participant ID") {
        retryProvider.getValueWithRetries(
          RetryFor.WaitingOnInitDependency,
          "get_participant_id",
          "Participant ID",
          participantAdminConnection.getParticipantId(),
          logger,
        )
      }
      domainTimeAutomationService = new DomainTimeAutomationService(
        config.domains.global.alias,
        participantAdminConnection,
        config.automation,
        clock,
        retryProvider,
        loggerFactory,
      )
      domainParamsAutomationService = new DomainParamsAutomationService(
        config.domains.global.alias,
        participantAdminConnection,
        config.automation,
        clock,
        retryProvider,
        loggerFactory,
      )
      newJoiningNodeInitializer = (
          joiningConfig: Option[SvOnboardingConfig.JoinWithKey],
          cometBftNode: Option[CometBftNode],
      ) =>
        new JoiningNodeInitializer(
          localSynchronizerNode,
          joiningConfig,
          participantId,
          config,
          amuletAppParameters.upgradesConfig,
          cometBftNode,
          ledgerClient,
          participantAdminConnection,
          clock,
          domainTimeAutomationService.domainTimeSync,
          domainParamsAutomationService.domainUnpausedSync,
          storage,
          loggerFactory,
          retryProvider,
          config.spliceInstanceNames,
        )
      // Ensure DSO party, DsoRules, AmuletRules, Mediator, and Sequencer nodes are setup
      // -------------------------------------------------------------------------------
      case (
        decentralizedSynchronizer,
        dsoPartyHosting,
        svStore,
        svAutomation,
        dsoStore,
        dsoAutomation,
      ) <-
      // We branch here on the type of onboarding config, as bootstrapping
      // a fresh dso is fundamentally different from joining an existing dso
      config.onboarding match {
        case Some(sv1Config: SvOnboardingConfig.FoundDso) =>
          for {
            cometBftNode <- SvUtil.mapToCometBftNode(
              cometBftClient,
              cometBftConfig,
              participantAdminConnection,
              logger,
              loggerFactory,
              retryProvider,
            )
            res <- appInitStep("SV1Initializer bootstrapping Dso") {
              val initializer = new SV1Initializer(
                localSynchronizerNode.getOrElse(
                  sys.error("SV1 must always specify a domain config")
                ),
                sv1Config,
                participantId,
                config,
                amuletAppParameters.upgradesConfig,
                cometBftNode,
                ledgerClient,
                participantAdminConnection,
                clock,
                domainTimeAutomationService.domainTimeSync,
                domainParamsAutomationService.domainUnpausedSync,
                storage,
                retryProvider,
                config.spliceInstanceNames,
                loggerFactory,
                config.parameters.enabledFeatures,
              )
              initializer.bootstrapDso()
            }
          } yield res
        case Some(joiningConfig: SvOnboardingConfig.JoinWithKey) =>
          for {
            cometBftNode <- SvUtil.mapToCometBftNode(
              cometBftClient,
              cometBftConfig,
              participantAdminConnection,
              logger,
              loggerFactory,
              retryProvider,
            )
            res <- appInitStep("JoiningNodeInitializer joining Dso with key") {
              val initializer = newJoiningNodeInitializer(Some(joiningConfig), cometBftNode)
              initializer.joinDsoAndOnboardNodes()
            }
          } yield res
        case Some(domainMigrationConfig: SvOnboardingConfig.DomainMigration) =>
          appInitStep("DomainMigrationInitializer initializing node from dump") {
            new DomainMigrationInitializer(
              localSynchronizerNode.getOrElse(
                sys.error("It must always specify a domain config for Domain Migration")
              ),
              domainMigrationConfig,
              participantId,
              cometBftConfig,
              cometBftClient,
              config,
              amuletAppParameters.upgradesConfig,
              None,
              ledgerClient,
              participantAdminConnection,
              clock,
              domainTimeAutomationService.domainTimeSync,
              domainParamsAutomationService.domainUnpausedSync,
              storage,
              loggerFactory,
              retryProvider,
              config.spliceInstanceNames,
              newJoiningNodeInitializer,
              config.parameters.enabledFeatures,
            ).migrateDomain()
          }
        case None =>
          for {
            cometBftNode <- SvUtil.mapToCometBftNode(
              cometBftClient,
              cometBftConfig,
              participantAdminConnection,
              logger,
              loggerFactory,
              retryProvider,
            )
            res <- {
              val initializer = newJoiningNodeInitializer(None, cometBftNode)
              initializer.joinDsoAndOnboardNodes()
            }
          } yield res
      }
      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        decentralizedSynchronizer,
        svAutomation.connection(SpliceLedgerConnectionPriority.Low),
        loggerFactory,
      )

      (_, _, isDevNet, _, _, _) <- (
        // We create the validator user only after the DSO party migration and DAR uploads have completed. This avoids two issues:
        // 1. The ValidatorLicense has both the DSO and the SV as a stakeholder.
        //    That can cause problems during the DSO party migration because the contract is imported there
        //    but could also be imported through the stream of the SV party. By only creating the validator user here
        //    we ensure that the party migration has been completed before the contract is created.
        appInitStep("Initialize validator") {
          SvApp.initializeValidator(
            dsoAutomation,
            config,
            retryProvider,
            logger,
          )
        },
        // Ensure Daml-level invariants for the SV
        // ----------------------------------------

        // At this point the complex setup of DSO party, sequencer, and mediators is done
        // What remains is setting up some SV-level Daml state.
        appInitStep("Expect configured validator onboardings") {
          expectConfiguredValidatorOnboardings(
            svAutomation,
            decentralizedSynchronizer,
            clock,
          )
        },
        appInitStep("Get AmuletRules to determine if we are in a DevNet") {
          retryProvider.getValueWithRetriesNoPretty(
            RetryFor.WaitingOnInitDependency,
            "get_amulet_rules",
            "get AmuletRules to determine if we are in a DevNet",
            dsoStore.getAmuletRules().map(amuletRules => amuletRules.payload.isDevNet),
            logger,
          )
        },
        appInitStep("Ensure amulet price has a vote") {
          config.initialAmuletPriceVote
            .map(
              ensureAmuletPriceVoteHasAmuletPrice(
                _,
                dsoAutomation,
                logger,
              )
            )
            .getOrElse(Future.unit)
        },
        appInitStep("Wait until configured onboarding contracts have been created") {
          waitUntilConfiguredOnboardingContractsHaveBeenCreated(svStore)
        },
        localSynchronizerNode match {
          case Some(node) =>
            if (!config.skipSynchronizerInitialization) {
              appInitStep(
                "Ensure that the local mediators's sequencer request amplification config is up to date"
              ) {
                // Normally we set this up during mediator init
                // but if the config changed without a mediator reset we need to update it here.
                node.ensureMediatorSequencerRequestAmplification()
              }
            } else {
              logger.info(
                "Skipping mediator sequencer amplification configuration because skipSynchronizerInitialization is enabled"
              )
              Future.unit
            }
          case None => Future.unit
        },
      ).tupled

      // We're registering the trafficBalanceService on the LedgerClient after all the SV onboarding steps
      // because we do not want the onboarding to be throttled by the balance check.
      trafficBalanceService = newTrafficBalanceService(participantAdminConnection)
      _ = ledgerClient.registerTrafficBalanceService(trafficBalanceService)

      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl, connectionTimeout, readTimeout) =>
          new RSAVerifier(
            audience,
            jwksUrl,
            RSAVerifier.TimeoutsConfig(connectionTimeout, readTimeout),
          )
      }

      // Start the servers for the SvApp's APIs
      // ---------------------------------------

      initialRound <- svAutomation
        .connection(SpliceLedgerConnectionPriority.Low)
        .lookupUserMetadata(config.ledgerApiUser, INITIAL_ROUND_USER_METADATA_KEY)
        .flatMap {
          case Some(round) => Future.successful(round)
          case None =>
            throw new IllegalStateException("No initial round specified in user's metadata")
        }

      handler = new HttpSvHandler(
        config.ledgerApiUser,
        svAutomation,
        dsoAutomation,
        isDevNet,
        config,
        clock,
        participantAdminConnection,
        localSynchronizerNode,
        retryProvider,
        new DsoPartyMigration(
          svAutomation,
          dsoAutomation,
          participantAdminConnection,
          retryProvider,
          dsoPartyHosting,
          loggerFactory,
        ),
        cometBftClient,
        loggerFactory,
        config.localSynchronizerNode.exists(_.sequencer.isBftSequencer),
        initialRound,
      )

      adminHandler = new HttpSvAdminHandler(
        config,
        config.domainMigrationDumpPath,
        amuletAppParameters.upgradesConfig,
        svAutomation,
        dsoAutomation,
        cometBftClient,
        localSynchronizerNode,
        participantAdminConnection,
        new DomainDataSnapshotGenerator(
          participantAdminConnection,
          localSynchronizerNode
            .getOrElse(
              sys.error("SV app should always have a sequencer connection for domain migrations")
            )
            .sequencerAdminConnection,
          dsoStore,
          new AcsExporter(
            participantAdminConnection,
            retryProvider,
            config.parameters.enabledFeatures.enableNewAcsExport,
            loggerFactory,
          ),
          retryProvider,
          loggerFactory,
        ),
        clock,
        retryProvider,
        packageVersionSupport,
        timeouts,
        loggerFactory,
      )
      httpRateLimiter = new HttpRateLimiter(
        config.parameters.rateLimiting,
        metrics.openTelemetryMetricsFactory,
      )

      route = cors(
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
            val errorHandler = new HttpErrorHandler(loggerFactory)
            def buildOperation(service: String, operation: String) = {
              metrics.httpServerMetrics
                .withMetrics(service)(operation)
                .tflatMap(_ => {
                  httpRateLimiter.withRateLimit(service)(operation).tflatMap { _ =>
                    config.parameters.customTimeouts.get(operation) match {
                      case Some(customTimeout) =>
                        withRequestTimeout(
                          customTimeout.duration,
                          errorHandler.timeoutHandler(customTimeout.duration, _)(traceContext),
                        )
                      case None => Directive.Empty
                    }
                  }
                })
            }

            errorHandler.directive(traceContext) {
              concat(
                SvResource.routes(
                  handler,
                  operation =>
                    buildOperation("sv", operation)
                      .tflatMap(_ => provide(traceContext)),
                ),
                SvAdminResource.routes(
                  adminHandler,
                  operation =>
                    buildOperation("svAdmin", operation)
                      .tflatMap(_ =>
                        AdminAuthExtractor(
                          verifier,
                          svStore.key.svParty,
                          svAutomation.connection(SpliceLedgerConnectionPriority.Low),
                          loggerFactory,
                          "splice sv admin realm",
                        )(traceContext)(operation)
                      ),
                ),
              )
            }
          }
        }

      }
      _ = adminRoutes.updateRoute(route)
    } yield {
      SvApp.State(
        participantAdminConnection,
        localSynchronizerNode,
        storage,
        domainTimeAutomationService,
        domainParamsAutomationService,
        svStore,
        dsoStore,
        svAutomation,
        dsoAutomation,
        adminHandler,
        logger,
        timeouts,
        httpClient,
        templateDecoder,
      )
    }
  }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  protected[this] override def automationServices(st: SvApp.State) =
    Seq(DsoDelegateBasedAutomationService, st.svAutomation, st.dsoAutomation)

  private def newTrafficBalanceService(participantAdminConnection: ParticipantAdminConnection) = {
    TrafficBalanceService(
      _ => Future.successful(Some(config.domains.global.reservedTraffic)),
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
  )(implicit tc: TraceContext): Future[Unit] = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "onboarding_contracts",
      "Onboarding contracts have been created", {
        val expectedValidatorOnboardingSecrets = config.expectedValidatorOnboardings.map(_.secret)
        for {
          createdValidatorOnboardingSecrets <- MonadUtil.sequentialTraverse(
            expectedValidatorOnboardingSecrets
          ) { secret =>
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
      svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
      decentralizedSynchronizer: SynchronizerId,
      clock: Clock,
  )(implicit tc: TraceContext): Future[List[Unit]] = {
    if (
      config.expectedValidatorOnboardings
        .map(_.secret)
        .toSet
        .size != config.expectedValidatorOnboardings.size
    ) {
      sys.error("Expected onboarding secrets must be unique! Check your SV app config.")
    }
    Future.traverse(config.expectedValidatorOnboardings)(c =>
      SvApp
        .prepareValidatorOnboarding(
          ValidatorOnboardingSecret(svStoreWithIngestion.store.key.svParty, c.secret, None),
          c.expiresIn,
          svStoreWithIngestion,
          decentralizedSynchronizer,
          clock,
          logger,
          retryProvider,
        )
        .map {
          case Left(reason) => logger.info(s"Did not prepare validator onboarding: $reason")
          case Right(()) => ()
        }
    )
  }

  private def ensureAmuletPriceVoteHasAmuletPrice(
      defaultAmuletPriceVote: BigDecimal,
      dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
      logger: TracedLogger,
  )(implicit tc: TraceContext): Future[Either[String, Unit]] =
    dsoStoreWithIngestion.store.lookupAmuletPriceVoteByThisSv().flatMap {
      case Some(vote) if vote.payload.amuletPrice.toScala.isDefined =>
        logger.info(s"A amulet price vote with a defined amulet price already exists")
        Future.successful(Right(()))
      case _ =>
        retryProvider.retry(
          RetryFor.WaitingOnInitDependency,
          "update_amulet_price",
          "Update amulet price vote to configured initial amulet price vote",
          SvApp
            .updateAmuletPriceVote(
              defaultAmuletPriceVote,
              dsoStoreWithIngestion,
              logger,
            ),
          logger,
        )
    }
}

object SvApp {
  case class State(
      participantAdminConnection: ParticipantAdminConnection,
      localSynchronizerNode: Option[LocalSynchronizerNode],
      storage: Storage,
      domainTimeAutomationService: DomainTimeAutomationService,
      domainParamsAutomationService: DomainParamsAutomationService,
      svStore: SvSvStore,
      dsoStore: SvDsoStore,
      svAutomation: SvSvAutomationService,
      dsoAutomation: SvDsoAutomationService,
      svAdminHandler: HttpSvAdminHandler,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
      httpClient: HttpClient,
      decoder: TemplateJsonDecoder,
  ) extends FlagCloseableAsync
      with HasHealth {
    override def isHealthy: Boolean =
      storage.isActive && svAutomation.isHealthy && dsoAutomation.isHealthy

    override def closeAsync(): Seq[AsyncOrSyncCloseable] =
      Seq(
        SyncCloseable(
          s"Domain connections",
          localSynchronizerNode.foreach(_.close()),
        ),
        SyncCloseable(
          s"Participant Admin connection",
          participantAdminConnection.close(),
        ),
        SyncCloseable("sv automation", svAutomation.close()),
        SyncCloseable("dso automation", dsoAutomation.close()),
        SyncCloseable("sv store", svStore.close()),
        SyncCloseable("dso store", dsoStore.close()),
        SyncCloseable("domain time automation", domainTimeAutomationService.close()),
        SyncCloseable("domain params automation", domainParamsAutomationService.close()),
        SyncCloseable("admin handler", svAdminHandler.close()),
        SyncCloseable("storage", storage.close()),
      )
  }

  // TODO(DACH-NY/canton-network-node#5364): move this and like functions into appropriate utility namespaces
  def prepareValidatorOnboarding(
      secret: ValidatorOnboardingSecret,
      expiresIn: NonNegativeFiniteDuration,
      svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
      decentralizedSynchronizer: SynchronizerId,
      clock: Clock,
      logger: TracedLogger,
      retryProvider: RetryProvider,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Either[String, Unit]] = {
    val svStore = svStoreWithIngestion.store

    // If the secret contains partyHint, use it as a single source of truth
    val (svParty, rawSecret, secretValue) = secret.partyHint match {
      case Some(hint) =>
        val sv = secret.sponsoringSv
        (
          sv,
          secret.secret,
          JsonOnboardingSecret(sv.toProtoPrimitive, secret.secret, hint).asJson.noSpaces,
        )
      case None => (svStore.key.svParty, secret.secret, secret.secret)
    }

    val validatorOnboarding = new splice.validatoronboarding.ValidatorOnboarding(
      svParty.toProtoPrimitive,
      secretValue,
      (clock.now + expiresIn.toInternal).toInstant,
    ).create()

    for {
      res <- svStore.lookupUsedSecretWithOffset(rawSecret).flatMap {
        case QueryResult(_, Some(usedSecret)) =>
          val validator = usedSecret.payload.validator
          Future.successful(
            Left(s"This secret has already been used before, for onboarding validator $validator")
          )
        case QueryResult(offset, None) =>
          svStore.lookupValidatorOnboardingBySecretWithOffset(rawSecret).flatMap {
            case QueryResult(_, Some(_)) =>
              Future.successful(
                Left("A validator onboarding contract with this secret already exists.")
              )
            case QueryResult(_, None) =>
              for {
                _ <- retryProvider.retryForClientCalls(
                  "prepare_validator_onboarding",
                  "Create a validator onboarding contract with a secret",
                  svStoreWithIngestion
                    .connection(SpliceLedgerConnectionPriority.Low)
                    .submit(actAs = Seq(svParty), readAs = Seq.empty, update = validatorOnboarding)
                    .withDedup(
                      commandId = SpliceLedgerConnection
                        .CommandId(
                          "org.lfdecentralizedtrust.splice.sv.expectValidatorOnboarding",
                          Seq(svParty),
                          secretValue, // not a leak as this gets hashed before it's used
                        ),
                      deduplicationOffset = offset,
                    )
                    .withSynchronizerId(synchronizerId = decentralizedSynchronizer)
                    .yieldUnit(),
                  logger,
                )
              } yield {
                logger.info("Created new ValidatorOnboarding contract.")
                Right(())
              }
          }
      }
    } yield res
  }

  def updateAmuletPriceVote(
      desiredAmuletPrice: BigDecimal,
      dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Either[String, Unit]] = {
    val dsoStore = dsoStoreWithIngestion.store
    dsoStore.lookupAmuletPriceVoteByThisSv().flatMap {
      case Some(vote) if vote.payload.amuletPrice.toScala.contains(desiredAmuletPrice.bigDecimal) =>
        logger.info(s"Amulet price vote is already set to $desiredAmuletPrice")
        Future.successful(Right(()))
      case Some(vote) =>
        for {
          dsoRules <- dsoStore.getDsoRules()
          cmd = dsoRules.exercise(
            _.exerciseDsoRules_UpdateAmuletPriceVote(
              dsoStore.key.svParty.toProtoPrimitive,
              vote.contractId,
              desiredAmuletPrice.bigDecimal,
            )
          )
          _ <- dsoStoreWithIngestion
            .connection(SpliceLedgerConnectionPriority.Low)
            .submit(
              actAs = Seq(dsoStore.key.svParty),
              readAs = Seq(dsoStore.key.dsoParty),
              update = cmd,
            )
            .noDedup
            .yieldUnit()
        } yield Right(())
      case None =>
        Future.successful(
          Left(
            s"No cc price vote contract found for this SV. It is not expected as it should be created when the SV was added to DSO,"
          )
        )
    }
  }

  def createVoteRequest(
      requester: String,
      action: Json,
      reasonUrl: String,
      reasonDescription: String,
      expiration: Json,
      effectiveTime: Optional[Instant],
      dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
      templateJsonDecoder: TemplateJsonDecoder,
  ): Future[Either[String, splice.dsorules.VoteRequest.ContractId]] = {
    val decodedExpiration = templateJsonDecoder.decodeValue(
      RelTime.valueDecoder(),
      RelTime._packageId,
      "DA.Time.Types",
      "RelTime",
    )(expiration)
    val decodedAction = templateJsonDecoder.decodeValue(
      ActionRequiringConfirmation.valueDecoder(),
      ActionRequiringConfirmation._packageId,
      "Splice.DsoRules",
      "ActionRequiringConfirmation",
    )(action)
    dsoStoreWithIngestion.store
      .lookupVoteRequestByThisSvAndActionWithOffset(decodedAction)
      .flatMap {
        case QueryResult(_, Some(vote)) =>
          Future.successful(
            Left(s"This vote request has already been created ${vote.contractId}.")
          )
        case QueryResult(offset, None) =>
          for {
            res <- retryProvider.retryForClientCalls(
              "createVoteRequest",
              "createVoteRequest",
              for {
                dsoRules <- dsoStoreWithIngestion.store.getDsoRules()
                reason = new Reason(reasonUrl, reasonDescription)
                request = new DsoRules_RequestVote(
                  requester,
                  decodedAction,
                  reason,
                  java.util.Optional.of(decodedExpiration),
                  effectiveTime,
                )
                cmd = dsoRules.exercise(_.exerciseDsoRules_RequestVote(request))
                res <- dsoStoreWithIngestion
                  .connection(SpliceLedgerConnectionPriority.Low)
                  .submit(
                    actAs = Seq(dsoStoreWithIngestion.store.key.svParty),
                    readAs = Seq(dsoStoreWithIngestion.store.key.dsoParty),
                    cmd,
                  )
                  .withDedup(
                    commandId = SpliceLedgerConnection.CommandId(
                      "org.lfdecentralizedtrust.splice.sv.requestVote",
                      Seq(
                        dsoStoreWithIngestion.store.key.dsoParty,
                        dsoStoreWithIngestion.store.key.svParty,
                      ),
                      action.toString,
                    ),
                    deduplicationOffset = offset,
                  )
                  .yieldResult()
              } yield res,
              logger,
            )
          } yield Right(res.exerciseResult.voteRequest)
      }
  }

  def castVote(
      trackingCid: splice.dsorules.VoteRequest.ContractId,
      isAccepted: Boolean,
      reasonUrl: String,
      reasonDescription: String,
      dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Either[String, splice.dsorules.VoteRequest.ContractId]] = {
    dsoStoreWithIngestion.store
      .lookupVoteByThisSvAndVoteRequestWithOffset(trackingCid)
      .flatMap { case QueryResult(_, _) =>
        for {
          dsoRules <- dsoStoreWithIngestion.store.getDsoRules()
          res <- retryProvider.retryForClientCalls(
            "castVote",
            "castVote",
            for {
              resolvedVoteRequest <- dsoStoreWithIngestion.store.getVoteRequest(trackingCid)
              resolvedCid = resolvedVoteRequest.contractId
              reason = new Reason(reasonUrl, reasonDescription)
              cmd = dsoRules.exercise(
                _.exerciseDsoRules_CastVote(
                  resolvedCid,
                  new Vote(
                    dsoStoreWithIngestion.store.key.svParty.toProtoPrimitive,
                    isAccepted,
                    reason,
                    Optional.empty(), // optCastAt
                  ),
                )
              )
              res <- dsoStoreWithIngestion
                .connection(SpliceLedgerConnectionPriority.Low)
                .submit(
                  actAs = Seq(dsoStoreWithIngestion.store.key.svParty),
                  readAs = Seq(dsoStoreWithIngestion.store.key.dsoParty),
                  update = cmd,
                )
                .noDedup
                .yieldResult()
            } yield res,
            logger,
          )
        } yield Right(res.exerciseResult.voteRequest)
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
        if (token.dsoParty == svStore.key.dsoParty) Right(())
        else authFailure("wrong DSO party", s"${token.dsoParty} != ${svStore.key.dsoParty}")
    } yield (token.candidateParty, token.candidateName, approvedSv.rewardWeightBps)
  }

  private[sv] def isSv(
      name: String,
      party: PartyId,
      dsoRules: Contract.Has[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
  ): Boolean =
    dsoRules.payload.svs.asScala
      .get(party.toProtoPrimitive)
      .exists(_.name == name)

  private[sv] def validateSvNamespace(
      candidateParty: PartyId,
      candidateParticipantId: ParticipantId,
  ): Boolean = candidateParty.uid.namespace == candidateParticipantId.uid.namespace

  private[sv] def validateCandidateSv(
      candidateParty: PartyId,
      candidateName: String,
      dsoRules: Contract.Has[DsoRules.ContractId, DsoRules],
  ): Either[Status, Unit] = {
    for {
      _ <- Either.cond(
        !SvApp.isSvParty(candidateParty, dsoRules),
        (),
        Status.ALREADY_EXISTS.withDescription(
          s"An SV with party ID $candidateParty already exists."
        ),
      )
      _ <-
        if (!SvApp.isDevNet(dsoRules)) {
          SvApp
            .getDsoPartyFromName(candidateName, dsoRules)
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

  private[sv] def isSvParty(
      party: PartyId,
      dsoRules: Contract.Has[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
  ): Boolean = dsoRules.payload.svs.containsKey(party.toProtoPrimitive)

  private[sv] def isSvName(
      name: String,
      dsoRules: Contract.Has[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
  ): Boolean = getDsoPartyFromName(name, dsoRules).isDefined

  private[sv] def getDsoPartyFromName(
      name: String,
      dsoRules: Contract.Has[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
  ): Option[String] = {
    dsoRules.payload.svs.asScala.collectFirst {
      case (partyId, svInfo) if svInfo.name == name => partyId
    }
  }

  private[sv] def isDevNet(
      dsoRules: Contract.Has[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules]
  ): Boolean = dsoRules.payload.isDevNet

  private def initializeValidator(
      dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
      config: SvAppBackendConfig,
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Unit] = {
    val store = dsoStoreWithIngestion.store
    val svParty = store.key.svParty
    logger.debug("Receiving or creating validator license for SV party")
    for {
      _ <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "create_validator_license",
        "Create validator license for SV party",
        for {
          dsoRules <- store.getDsoRules()
          validatorLicense <- store.lookupValidatorLicenseWithOffset(
            svParty
          )
          _ <- validatorLicense match {
            case QueryResult(_, Some(_)) =>
              logger.info("Validator license for SV party already exists")
              Future.unit
            case QueryResult(offset, None) =>
              logger.debug("Trying to create validator license for SV party")
              val dsoParty = store.key.dsoParty
              val cmd = dsoRules.exercise(
                _.exerciseDsoRules_OnboardValidator(
                  svParty.toProtoPrimitive,
                  svParty.toProtoPrimitive,
                  Some(BuildInfo.compiledVersion).toJava,
                  Some(config.contactPoint).toJava,
                )
              )

              for {
                _ <- dsoStoreWithIngestion
                  .connection(SpliceLedgerConnectionPriority.Low)
                  .submit(
                    actAs = Seq(svParty),
                    readAs = Seq(dsoParty),
                    cmd,
                  )
                  .withDedup(
                    commandId = SpliceLedgerConnection.CommandId(
                      "org.lfdecentralizedtrust.splice.sv.createSvValidatorLicense",
                      Seq(
                        dsoParty,
                        svParty,
                      ),
                      svParty.toProtoPrimitive,
                    ),
                    deduplicationOffset = offset,
                  )
                  .yieldUnit()
              } yield {
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
      _ <- dsoStoreWithIngestion
        .connection(SpliceLedgerConnectionPriority.Low)
        .createUserWithPrimaryParty(
          config.validatorLedgerApiUser,
          svParty,
          Seq(User.Right.ParticipantAdmin.INSTANCE),
        )
    } yield ()
  }
}
