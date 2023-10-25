package com.daml.network.validator

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.directives.BasicDirectives
import cats.implicits.{catsSyntaxApplicativeByValue as _, *}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.User
import com.daml.network.admin.api.TraceContextDirectives.withTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.auth.*
import com.daml.network.config.{CNThresholds, NetworkAppClientConfig, SharedCNNodeAppParameters}
import com.daml.network.environment.*
import com.daml.network.http.v0.app_manager.AppManagerResource
import com.daml.network.http.v0.app_manager_admin.AppManagerAdminResource
import com.daml.network.http.v0.app_manager_public.AppManagerPublicResource
import com.daml.network.http.v0.json_api_public.JsonApiPublicResource
import com.daml.network.http.v0.external.common_admin.CommonAdminResource
import com.daml.network.http.v0.external.wallet.WalletResource as ExternalWalletResource
import com.daml.network.http.v0.validator.ValidatorResource
import com.daml.network.http.v0.validator_admin.ValidatorAdminResource
import com.daml.network.http.v0.validator_public.ValidatorPublicResource
import com.daml.network.http.v0.wallet.WalletResource as InternalWalletResource
import com.daml.network.scan.admin.api.client.{MinimalScanConnection, ScanConnection}
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.setup.ParticipantInitializer
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.{AcsStoreDump, CNNodeAppStoreWithIngestion}
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.util.{CoinConfigSchedule, HasHealth, UploadablePackage}
import com.daml.network.validator.admin.AppManagerService
import com.daml.network.validator.admin.http.*
import com.daml.network.validator.automation.ValidatorAutomationService
import com.daml.network.validator.config.{
  AppInstance,
  ValidatorAppBackendConfig,
  ValidatorOnboardingConfig,
}
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.daml.network.validator.store.{ParticipantIdentitiesStore, ValidatorStore}
import com.daml.network.validator.util.{OAuth2Manager, ValidatorUtil}
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.admin.http.{HttpExternalWalletHandler, HttpWalletHandler}
import com.digitalasset.canton.{DomainAlias, SequencerAlias}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import com.daml.network.directory.admin.http.HttpExternalDirectoryHandler
import com.daml.network.http.v0.external.directory.DirectoryResource
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.SvcSequencer
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.data.CantonTimestamp

/** Class representing a Validator app instance. */
class ValidatorApp(
    override val name: InstanceName,
    val config: ValidatorAppBackendConfig,
    val coinAppParameters: SharedCNNodeAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    metrics: ValidatorAppMetrics,
)(implicit
    ac: ActorSystem,
    esf: ExecutionSequencerFactory,
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends CNNode[ValidatorApp.State](
      config.ledgerApiUser,
      config.participantClient,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      metrics,
    )
    with BasicDirectives {

  override def packages =
    super.packages ++ DarResources.wallet.all ++ DarResources.directoryService.all ++ DarResources.appManager.all

  override def preInitializeBeforeLedgerConnection(): Future[Unit] = for {
    // TODO(tech-debt) consider removing early version check once we switch to a non-dev Canton protocol version
    _ <- ensureVersionMatch(config.scanClient)
    _ <- ParticipantInitializer.ensureParticipantInitializedWithExpectedId(
      config.participantClient,
      config.participantBootstrappingDump,
      loggerFactory,
      retryProvider,
      clock,
    )
  } yield ()

  override def preInitializeAfterLedgerConnection(
      connection: CNLedgerConnection,
      ledgerClient: CNLedgerClient,
  ) =
    for {
      _ <- config.appManager.traverse_ { appManagerConfig =>
        connection.ensureIdentityProviderConfig(
          CNLedgerConnection.APP_MANAGER_IDENTITY_PROVIDER_ID,
          appManagerConfig.issuerUrl.toString,
          appManagerConfig.jwksUri.toString,
          appManagerConfig.audience,
        )
      }
      _ <-
        withParticipantAdminConnection { participantAdminConnection =>
          for {
            scanConnection <- ScanConnection(
              ledgerClient,
              config.scanClient,
              clock,
              retryProvider,
              loggerFactory,
            )
            _ <- ensureGlobalDomainRegistered(participantAdminConnection, scanConnection)
            _ <- ensureExtraDomainsRegistered(participantAdminConnection)
            // Note that for the validator of an SV app, the user will be created by the SV app with a
            // primary party set to the SV app already so this is a noop.
            _ <- connection.ensureUserPrimaryPartyIsAllocated(
              config.ledgerApiUser,
              config.validatorPartyHint
                .getOrElse(
                  CNLedgerConnection.sanitizeUserIdToPartyString(config.ledgerApiUser)
                ),
              participantAdminConnection,
            ) whenA !config.svValidator
          } yield ()
        }
    } yield ()

  private def setupWalletDars(
      participantAdminConnection: ParticipantAdminConnection
  ): Future[Unit] = {
    logger.info(s"Attempting to setup wallet...")
    val darFiles = Seq(
      UploadablePackage.fromResource(DarResources.wallet.bootstrap),
      UploadablePackage.fromResource(DarResources.directoryService.bootstrap),
    )
    for {
      _ <- participantAdminConnection.uploadDarFiles(darFiles, RetryFor.WaitingOnInitDependency)
    } yield {
      logger.info(
        s"Finished wallet setup"
      )
    }
  }

  private def setupAppInstance(
      name: String,
      instance: AppInstance,
      validatorParty: PartyId,
      storeWithIngestion: CNNodeAppStoreWithIngestion[ValidatorStore],
      participantAdminConnection: ParticipantAdminConnection,
      domainId: DomainId,
  ): Future[Unit] = {
    logger.info(s"Attempting to setup app $name...")
    for {
      _ <- instance.dars.traverse_(dar =>
        participantAdminConnection.uploadDarFile(
          dar,
          RetryFor.WaitingOnInitDependency,
        )
      )
      party <- storeWithIngestion.connection.getOrAllocateParty(
        instance.serviceUser,
        Seq(new User.Right.CanReadAs(validatorParty.toProtoPrimitive)),
        participantAdminConnection,
      )
      _ <- ValidatorUtil
        .onboard(
          instance.walletUser.getOrElse(instance.serviceUser),
          Some(party),
          storeWithIngestion,
          validatorUserName = config.ledgerApiUser,
          // we're initializing so CoinRules is guaranteed to be on domainId
          getCoinRulesDomain = () => _ => Future successful domainId,
          participantAdminConnection,
          retryProvider,
          logger,
        )
    } yield {
      logger.info(
        s"Setup app $name with service user ${instance.serviceUser}, wallet user ${instance.walletUser}  primary party $party, and uploaded ${instance.dars}."
      )
    }
  }

  private def ensureValidatorIsOnboarded(
      store: ValidatorStore,
      validatorParty: PartyId,
      onboardingConfig: Option[ValidatorOnboardingConfig],
  ): Future[Unit] = {
    store.lookupValidatorLicenseWithOffset().flatMap {
      case QueryResult(_, Some(_)) =>
        logger.info("ValidatorLicense found => already onboarded.")
        Future.successful(())
      case _ =>
        onboardingConfig match {
          case Some(oc) =>
            for {
              _ <- requestOnboarding(oc.svClient.adminApi, validatorParty, oc.secret)
              _ <- waitForValidatorLicense(store)
            } yield ()
          case None => sys.error("Not onboarded but no onboarding config found; exiting.")
        }
    }
  }

  private def waitForValidatorLicense(
      store: ValidatorStore
  ): Future[Unit] = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      show"ValidatorLicense for ${store.key.validatorParty} is visible",
      for {
        validatorLicenseResult <- store.lookupValidatorLicenseWithOffset()
        _ <- validatorLicenseResult match {
          case QueryResult(_, Some(_)) => Future.successful(())
          case _ =>
            throw Status.NOT_FOUND
              .withDescription(
                show"ValidatorLicense for ${store.key.validatorParty}"
              )
              .asRuntimeException()
        }
      } yield (),
      logger,
    )
  }

  private def waitForSequencerConnectionsFromScan(
      scanConnection: ScanConnection,
      logger: TracedLogger,
      retryProvider: RetryProvider,
  ) = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "valid sequencer connections from scan is non empty",
      ValidatorApp
        .getSequencerConnectionsFromScan(
          scanConnection,
          logger,
          clock.now,
        )
        .map { connections =>
          if (connections.isEmpty)
            throw Status.NOT_FOUND
              .withDescription(
                s"sequencer connections is empty"
              )
              .asRuntimeException()
        },
      logger,
    )
  }

  private def ensureVersionMatch(scanClient: ScanAppClientConfig): Future[Unit] =
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "version checked via scan",
      // we checkVersionCompatibility on every CN app connection
      withMinimalScanConnection(scanClient)(_.checkActive()),
      logger,
    )

  private def withMinimalScanConnection[T](
      config: ScanAppClientConfig
  )(f: MinimalScanConnection => Future[T]): Future[T] =
    MinimalScanConnection(config, retryProvider, loggerFactory).flatMap(con =>
      f(con).andThen(_ => con.close())
    )

  private def withSvConnection[T](
      svConfig: NetworkAppClientConfig
  )(f: SvConnection => Future[T]): Future[T] =
    SvConnection(svConfig, retryProvider, loggerFactory).flatMap(con =>
      f(con).andThen(_ => con.close())
    )

  private def requestOnboarding(
      svConfig: NetworkAppClientConfig,
      validatorParty: PartyId,
      secret: String,
  ): Future[Unit] = {
    logger.info(s"Requesting to be onboarded by SV at: ${svConfig.url}")
    retryProvider.retryForAutomation(
      "request onboarding",
      withSvConnection(svConfig)(_.onboardValidator(validatorParty, secret)),
      logger,
    )
  }

  private def withParticipantAdminConnection[T](f: ParticipantAdminConnection => Future[T]) = {
    val participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      loggerFactory,
      retryProvider,
      clock,
    )
    f(participantAdminConnection).andThen { _ => participantAdminConnection.close() }
  }

  private def ensureGlobalDomainRegistered(
      participantAdminConnection: ParticipantAdminConnection,
      scanConnection: ScanConnection,
  ): Future[Unit] = {
    if (config.svValidator)
      ensureDomainRegistered(
        config.domains.global.alias,
        config.domains.global.url,
        participantAdminConnection,
      )
    else
      ensureDomainRegisteredFromScan(
        config.domains.global.alias,
        participantAdminConnection,
        scanConnection,
      )
  }

  private def ensureExtraDomainsRegistered(
      participantAdminConnection: ParticipantAdminConnection
  ): Future[Unit] =
    config.domains.extra.traverse_(domain =>
      ensureDomainRegistered(domain.alias, domain.url, participantAdminConnection)
    )

  private def ensureDomainRegistered(
      alias: DomainAlias,
      url: String,
      participantAdminConnection: ParticipantAdminConnection,
  ): Future[Unit] = {
    val domainConfig = DomainConnectionConfig(
      alias,
      SequencerConnections.single(GrpcSequencerConnection.tryCreate(url)),
    )
    participantAdminConnection.ensureDomainRegistered(
      domainConfig,
      RetryFor.WaitingOnInitDependency,
    )
  }

  private def ensureDomainRegisteredFromScan(
      alias: DomainAlias,
      participantAdminConnection: ParticipantAdminConnection,
      scanConnection: ScanConnection,
  ): Future[Unit] = {
    for {
      _ <- waitForSequencerConnectionsFromScan(
        scanConnection,
        logger,
        retryProvider,
      )
      sequencerConnections <- ValidatorApp.getSequencerConnectionsFromScan(
        scanConnection,
        logger,
        clock.now,
      )
      domainConfig = NonEmpty.from(sequencerConnections) match {
        case None =>
          sys.error("sequencer connections from scan is not expected to be empty.")
        case Some(nonEmptyConnections) =>
          DomainConnectionConfig(
            alias,
            SequencerConnections.many(
              nonEmptyConnections,
              CNThresholds.getSequencerConnectionsSizeThreshold(nonEmptyConnections.size),
            ),
          )
      }
      _ <- participantAdminConnection.ensureDomainRegistered(
        domainConfig,
        RetryFor.WaitingOnInitDependency,
      )
    } yield ()
  }

  private def newTrafficBalanceService(
      participantAdminConnection: ParticipantAdminConnection,
      scanConnection: ScanConnection,
  ) = {
    def lookupReservedTraffic(domainId: DomainId): Future[Option[NonNegativeLong]] = {
      config.domains.global.trafficReservedForTopupsO
        .fold(Future.successful(Option.empty[NonNegativeLong]))(trafficReservedForTopups => {
          for {
            coinRules <- scanConnection.getCoinRules()
            coinConfig = CoinConfigSchedule(coinRules).getConfigAsOf(clock.now)
            reservedTraffic = Option.when(
              coinConfig.globalDomain.requiredDomains.map.containsKey(domainId.toProtoPrimitive)
            )(trafficReservedForTopups)
          } yield reservedTraffic
        })
    }

    TrafficBalanceService(
      lookupReservedTraffic,
      participantAdminConnection,
      clock,
      config.domains.global.trafficBalanceCacheTimeToLive,
      loggerFactory,
    )
  }

  override def initialize(
      ledgerClient: CNLedgerClient,
      validatorParty: PartyId,
  ): Future[ValidatorApp.State] =
    for {
      _ <- Future.successful(())
      participantAdminConnection = new ParticipantAdminConnection(
        config.participantClient.adminApi,
        loggerFactory,
        retryProvider,
        clock,
      )
      _ <- setupWalletDars(participantAdminConnection)
      participantIdentitiesStore = new ParticipantIdentitiesStore(
        participantAdminConnection,
        config.participantIdentitiesBackup,
        clock,
        loggerFactory,
      )
      scanConnection <- ScanConnection(
        ledgerClient,
        config.scanClient,
        clock,
        retryProvider,
        loggerFactory,
      )
      svcParty <- scanConnection.getSvcPartyIdWithRetries()
      key = ValidatorStore.Key(
        validatorParty = validatorParty,
        svcParty = svcParty,
      )
      store = ValidatorStore(
        key,
        storage,
        loggerFactory,
        retryProvider,
      )
      walletManager =
        new UserWalletManager(
          ledgerClient,
          config.domains.global.alias,
          store,
          config.ledgerApiUser,
          config.automation,
          clock,
          config.treasury,
          storage: Storage,
          retryProvider,
          scanConnection,
          loggerFactory,
        )
      automation = new ValidatorAutomationService(
        config.automation,
        config.participantIdentitiesBackup,
        config.domains.global.buyExtraTraffic,
        config.appManager,
        config.svValidator,
        config.domains.global.alias,
        clock,
        walletManager,
        store,
        scanConnection,
        ledgerClient,
        participantAdminConnection,
        participantIdentitiesStore,
        retryProvider,
        loggerFactory,
      )
      _ = logger.info(
        s"Waiting for domain connection on ${config.domains.global.alias} before setting up app instances"
      )
      domainId <- store.domains.waitForDomainConnection(config.domains.global.alias)
      _ <- config.appInstances.toList.traverse({ case (name, instance) =>
        setupAppInstance(
          name,
          instance,
          validatorParty,
          automation,
          participantAdminConnection,
          domainId,
        )
      })
      // Receive the import crates for the validator party here, so that we can skip onboarding if there is a crate
      // containing a validator license. This MAY contend in a benign fashion with the crate receipt in the
      // 'UserWalletService' in case the validator app is restarted within its initialization sequence.
      _ <- AcsStoreDump.receiveCratesFor(
        validatorParty,
        (party: PartyId, tc0: TraceContext) => scanConnection.getImportShipment(party)(tc0),
        useReadAs = None,
        // Use the ValidatorStore's associated connection, so the later check whether a ValidatorLicense exists runs
        // against the store updated with the results of the crate import.
        automation.connection,
        retryProvider,
        logger,
      )
      _ <- ValidatorUtil.onboard(
        endUserName = config.validatorWalletUser.getOrElse(config.ledgerApiUser),
        knownParty = Some(validatorParty),
        automation,
        validatorUserName = config.ledgerApiUser,
        // we're initializing so CoinRules is guaranteed to be on domainId
        getCoinRulesDomain = () => _ => Future successful domainId,
        participantAdminConnection,
        retryProvider,
        logger,
      )
      _ <- ensureValidatorIsOnboarded(store, validatorParty, config.onboarding)

      // We're registering the trafficBalanceService on the LedgerClient after the validator has been onboarded
      // (in particular after the validator's wallet has been installed) because the top-up trigger relies on
      // the validator's WalletAppInstall contract to submit the top-up transaction and we do not want the wallet
      // installation or other validator onboarding steps to be throttled by the balance check.
      trafficBalanceService = newTrafficBalanceService(participantAdminConnection, scanConnection)
      _ = ledgerClient.registerTrafficBalanceService(trafficBalanceService)

      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl) => new RSAVerifier(audience, jwksUrl)
      }

      handler =
        new HttpValidatorHandler(
          automation,
          validatorUserName = config.ledgerApiUser,
          getCoinRulesDomain = scanConnection.getCoinRulesDomain,
          participantAdminConnection,
          retryProvider,
          loggerFactory,
        )

      adminHandler =
        new HttpValidatorAdminHandler(
          automation,
          participantIdentitiesStore,
          validatorUserName = config.ledgerApiUser,
          validatorWalletUserName = config.validatorWalletUser,
          getCoinRulesDomain = scanConnection.getCoinRulesDomain,
          participantAdminConnection,
          retryProvider = retryProvider,
          loggerFactory,
        )

      commonAdminHandler = new HttpAdminHandler(
        status
          .map(CNNodeStatus.fromNodeStatus)
          .map(NodeStatus.Success(_)),
        loggerFactory,
      )

      walletInternalHandler = new HttpWalletHandler(
        walletManager,
        scanConnection,
        loggerFactory,
        retryProvider,
      )

      walletExternalHandler = new HttpExternalWalletHandler(walletManager, loggerFactory)

      storeDomain <- store.domains.getDomainId(config.domains.global.alias)
      directoryExternalHandler = new HttpExternalDirectoryHandler(
        walletManager,
        storeDomain,
        config.domains.global.alias,
        retryProvider,
        loggerFactory,
      )

      publicHandler = new HttpValidatorPublicHandler(
        automation.store,
        config.ledgerApiUser,
        loggerFactory,
      )

      appManagerHandlersO <-
        config.appManager.traverse { config =>
          val dar = UploadablePackage.fromResource(DarResources.appManager.bootstrap)
          for {
            _ <- participantAdminConnection.uploadDarFiles(
              Seq(dar),
              RetryFor.WaitingOnInitDependency,
            )
            service = new AppManagerService(
              validatorParty,
              automation.connection,
              participantAdminConnection,
              automation.appManagerStore,
            )
            _ <- config.initialRegisteredApps.values.toList.traverse { app =>
              service.registerApp(
                app.providerUserId,
                app.config,
                new java.io.File(app.releaseFile),
                RetryFor.WaitingOnInitDependency,
              )
            }
            // TODO (#7458): use the endpoint implemented in #7516
//            _ <- config.initialInstalledApps.values.toList.traverse { app =>
//            }
          } yield {
            val oauth2Manager = new OAuth2Manager(config, loggerFactory)
            val appManagerAdminHandler = new HttpAppManagerAdminHandler(
              participantAdminConnection,
              automation.appManagerStore,
              service,
              retryProvider,
              loggerFactory,
            )
            val appManagerHandler = new HttpAppManagerHandler(
              config,
              automation.connection,
              automation.appManagerStore,
              oauth2Manager,
              loggerFactory,
            )
            val appManagerPublicHandler = new HttpAppManagerPublicHandler(
              config,
              participantAdminConnection,
              automation.appManagerStore,
              oauth2Manager,
              loggerFactory,
            )
            val jsonApiPublicHandler = new HttpJsonApiPublicHandler(
              config,
              loggerFactory,
            )
            (
              appManagerAdminHandler,
              appManagerHandler,
              appManagerPublicHandler,
              jsonApiPublicHandler,
            )
          }
        }

      routes = cors(
        CorsSettings(ac)
          .withAllowedMethods(
            List(
              HttpMethods.DELETE,
              HttpMethods.GET,
              HttpMethods.POST,
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
                (Seq(
                  ValidatorResource.routes(
                    handler,
                    AuthExtractor(verifier, loggerFactory, "canton network validator realm"),
                  ),
                  InternalWalletResource.routes(
                    walletInternalHandler,
                    AuthExtractor(verifier, loggerFactory, "canton network wallet realm"),
                  ),
                  ExternalWalletResource.routes(
                    walletExternalHandler,
                    AuthExtractor(verifier, loggerFactory, "canton network wallet realm"),
                  ),
                  DirectoryResource.routes(
                    directoryExternalHandler,
                    AuthExtractor(verifier, loggerFactory, "canton network directory realm"),
                  ),
                  ValidatorAdminResource.routes(
                    adminHandler,
                    operationId =>
                      AdminAuthExtractor(
                        verifier,
                        validatorParty,
                        automation.connection,
                        loggerFactory,
                        "canton network validator operator realm",
                      )(traceContext)(operationId),
                  ),
                  ValidatorPublicResource.routes(
                    publicHandler,
                    _ => provide(()),
                  ),
                  CommonAdminResource.routes(commonAdminHandler, _ => provide(traceContext)),
                ) ++
                  appManagerHandlersO.toList.flatMap {
                    case (adminHandler, handler, publicHandler, jsonApiHandler) =>
                      Seq(
                        AppManagerAdminResource.routes(
                          adminHandler,
                          operationId =>
                            AdminAuthExtractor(
                              verifier,
                              validatorParty,
                              automation.connection,
                              loggerFactory,
                              "app manager admin realm",
                            )(traceContext)(
                              operationId
                            ),
                        ),
                        AppManagerResource.routes(
                          handler,
                          AuthExtractor(verifier, loggerFactory, "app manager user realm"),
                        ),
                        AppManagerPublicResource.routes(
                          publicHandler,
                          _ => provide(()),
                        ),
                        JsonApiPublicResource.routes(
                          jsonApiHandler,
                          _ => provide(()),
                        ),
                      )
                  }): _*
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

    } yield {
      ValidatorApp.State(
        scanConnection,
        participantAdminConnection,
        storage,
        store,
        automation,
        walletManager,
        binding,
        timeouts,
        loggerFactory.getTracedLogger(ValidatorApp.State.getClass),
      )
    }

  override lazy val ports = Map("admin" -> config.adminApi.port)
}

object ValidatorApp {
  case class State(
      scanConnection: ScanConnection,
      participantAdminConnection: ParticipantAdminConnection,
      storage: Storage,
      store: ValidatorStore,
      automation: ValidatorAutomationService,
      walletManager: UserWalletManager,
      binding: Http.ServerBinding,
      timeouts: ProcessingTimeout,
      logger: TracedLogger,
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
        automation,
        walletManager,
        store,
        storage,
        scanConnection,
        participantAdminConnection,
      )(logger)
  }

  def getSequencerConnectionsFromScan(
      scanConnection: ScanConnection,
      logger: TracedLogger,
      domainTime: CantonTimestamp,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Seq[GrpcSequencerConnection]] = {
    for {
      globalDomainId <- scanConnection.getCoinRulesDomain()(traceContext)
      domainSequencers <- scanConnection.listSvcSequencers()
      maybeSequencers = domainSequencers.find(_.domainId == globalDomainId)
    } yield maybeSequencers.fold {
      logger.warn("global domain sequencer list not found.")
      Seq.empty[GrpcSequencerConnection]
    } { domainSequencer =>
      extractValidConnections(domainSequencer.sequencers, domainTime)
    }
  }

  private def extractValidConnections(
      sequencers: Seq[SvcSequencer],
      domainTime: CantonTimestamp,
  ): Seq[GrpcSequencerConnection] = {
    // sequencer connections will be ignore if they are with a invalid Alias, empty url or not yet available (`before availableAfter`)
    val validConnections = sequencers
      .collect {
        case SvcSequencer(_, url, svName, availableAfter)
            if url.nonEmpty && !domainTime.toInstant.isBefore(availableAfter) =>
          for {
            sequencerAlias <- SequencerAlias.create(svName)
            grpcSequencerConnection <- GrpcSequencerConnection.create(
              url,
              None,
              sequencerAlias,
            )
          } yield grpcSequencerConnection
      }
      .collect { case Right(conn) =>
        conn
      }
    validConnections
  }
}
