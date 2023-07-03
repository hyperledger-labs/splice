package com.daml.network.validator

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.directives.BasicDirectives
import cats.implicits.*
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.User
import com.daml.network.admin.api.TraceContextDirectives.newTraceContext
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.auth.{
  AdminAuthExtractor,
  AuthConfig,
  AuthExtractor,
  HMACVerifier,
  RSAVerifier,
}
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.config.{NetworkAppClientConfig, SharedCNNodeAppParameters}
import com.daml.network.environment.{
  CNLedgerClient,
  CNLedgerConnection,
  CNNode,
  CNNodeStatus,
  ParticipantAdminConnection,
}
import com.daml.network.http.v0.commonAdmin.CommonAdminResource
import com.daml.network.http.v0.validator.ValidatorResource
import com.daml.network.http.v0.validatorAdmin.ValidatorAdminResource
import com.daml.network.http.v0.wallet.WalletResource
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.util.{HasHealth, UploadablePackage}
import com.daml.network.validator.admin.http.{HttpValidatorAdminHandler, HttpValidatorHandler}
import com.daml.network.validator.automation.ValidatorAutomationService
import com.daml.network.validator.config.{
  AppInstance,
  ValidatorAppBackendConfig,
  ValidatorOnboardingConfig,
}
import com.daml.network.validator.store.{ParticipantIdentitiesStore, ValidatorStore}
import com.daml.network.validator.util.ValidatorUtil
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.admin.http.HttpWalletHandler
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TracerProvider
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Future}

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
    )
    with BasicDirectives {

  @nowarn("cat=unused")
  private def noLock[T](reason: String, f: () => Future[T]): Future[T] = f()

  private def noLock_(f: () => Future[Unit]): Future[Unit] = f()

  def withGlobalLock[A, B](f: ((String, () => Future[A]) => Future[A]) => Future[B]) =
    withSvConnection(
      config.foundingSvClient.adminApi
    )(svConnection => f(svConnection.withGlobalLock(_, _)))

  // Note that for the validator of an SV app, the user will be created by the SV app with a
  // primary party set to the SV app already so this is a noop.
  // For regular validators, this allocates a new user.
  override def preInitialize(connection: CNLedgerConnection) =
    for {
      // TODO(#5803) Consider removing this once Canton stops falling apart.
      // Wait for the sponsoring SV which also ensures the domain is initialized.
      _ <- config.onboarding.traverse_(waitUntilSponsorIsActive(_))
      _ <- withGlobalLock[Unit, Unit](lock =>
        lock(
          "Domain connection and primary party allocation of validatior",
          () =>
            withParticipantAdminConnection { participantAdminConnection =>
              for {
                _ <- ensureGlobalDomainRegistered(participantAdminConnection)
                _ <- ensureExtraDomainsRegistered(participantAdminConnection)
                _ <- connection.ensureUserPrimaryPartyIsAllocated(
                  config.ledgerApiUser,
                  config.validatorPartyHint
                    .getOrElse(
                      CNLedgerConnection.sanitizeUserIdToPartyString(config.ledgerApiUser)
                    ),
                  participantAdminConnection,
                  // We have an outer lock around both here so we don't need to lock here.
                  noLock,
                )
              } yield ()
            },
        )
      )
    } yield ()

  private def setupWalletDars(
      participantAdminConnection: ParticipantAdminConnection
  ): Future[Unit] = {
    logger.info(s"Attempting to setup wallet...")
    val darFiles = new UploadablePackage {
      lazy val packageId: String =
        installCodegen.WalletAppInstall.TEMPLATE_ID.getPackageId

      // See `Compile / resourceGenerators` in build.sbt
      lazy val resourcePath: String = "dar/wallet-0.1.0.dar"
    } +: Seq(new UploadablePackage {
      // should be the same as package dependency in wallet app
      lazy val packageId: String =
        ccV1Test.coin.CoinRulesV1Test.COMPANION.TEMPLATE_ID.getPackageId

      // See `Compile / resourceGenerators` in build.sbt
      lazy val resourcePath: String = "dar/canton-coin-0.1.1.dar"
    }).filter(_ => config.enableCoinRulesUpgrade)
    for {
      _ <- withGlobalLock[Unit, Unit](
        participantAdminConnection.uploadDarFiles(darFiles, _)
      )
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
      party <- withGlobalLock[PartyId, PartyId](lock =>
        lock(
          s"Allocate party for user ${instance.serviceUser}",
          () =>
            for {
              _ <- instance.dars.traverse_(dar =>
                participantAdminConnection.uploadDarFile(
                  dar,
                  noLock_,
                )
              )
              party <- storeWithIngestion.connection.getOrAllocateParty(
                instance.serviceUser,
                Seq(new User.Right.CanReadAs(validatorParty.toProtoPrimitive)),
                participantAdminConnection,
                // We rely on the outer lock so we don't need to lock here.
                noLock,
              )
              _ <- ValidatorUtil
                .onboard(
                  instance.walletUser.getOrElse(instance.serviceUser),
                  Some(party),
                  storeWithIngestion,
                  validatorUserName = config.ledgerApiUser,
                  domainId,
                  participantAdminConnection,
                  // We rely on the outer lock so we don't need to lock here.
                  noLock,
                  retryProvider,
                  logger,
                )
            } yield party,
        )
      )
    } yield {
      logger.info(
        s"Setup app $name with service user ${instance.serviceUser}, wallet user ${instance.walletUser}  primary party $party, and uploaded ${instance.dars}."
      )
    }
  }

  private def ensureOnboarded(
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
      show"ValidatorLicense for ${store.key.validatorParty} is visible",
      for {
        validatorLicenseResult <- store.lookupValidatorLicenseWithOffset()
        _ <- validatorLicenseResult match {
          case QueryResult(_, Some(_)) => Future.successful(())
          case _ =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(
                show"ValidatorLicense for ${store.key.validatorParty}"
              )
            )
        }
      } yield (),
      logger,
    )
  }

  private def withSvConnection[T](
      svConfig: NetworkAppClientConfig
  )(f: SvConnection => Future[T]): Future[T] =
    SvConnection(svConfig, retryProvider, loggerFactory).flatMap(con =>
      f(con).andThen(_ => con.close())
    )

  private def waitUntilSponsorIsActive(onboarding: ValidatorOnboardingConfig): Future[Unit] =
    retryProvider.waitUntil(
      "Sponsor SV is active",
      withSvConnection(onboarding.svClient.adminApi)(_.checkActive()),
      logger,
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
      participantAdminConnection: ParticipantAdminConnection
  ): Future[Unit] = ensureDomainRegistered(
    config.domains.global.alias,
    config.domains.global.url,
    participantAdminConnection,
  )

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
    participantAdminConnection.ensureDomainRegistered(domainConfig)
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
        ledgerClient.connection("ParticipantIdentitiesStore", loggerFactory),
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
        config.domains,
        loggerFactory,
        retryProvider,
      )
      walletManager =
        new UserWalletManager(
          ledgerClient,
          config.domains.global.alias,
          store,
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
      _ <- withGlobalLock[Unit, PartyId](
        ValidatorUtil.onboard(
          endUserName = config.validatorWalletUser.getOrElse(config.ledgerApiUser),
          knownParty = Some(validatorParty),
          automation,
          validatorUserName = config.ledgerApiUser,
          domainId,
          participantAdminConnection,
          _,
          retryProvider,
          logger,
        )
      )
      _ <- ensureOnboarded(store, validatorParty, config.onboarding)
      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl) => new RSAVerifier(audience, jwksUrl)
      }

      handler <- withGlobalLock[Unit, HttpValidatorHandler](lock =>
        Future.successful(
          new HttpValidatorHandler(
            automation,
            validatorUserName = config.ledgerApiUser,
            domainId = domainId,
            participantAdminConnection,
            lock,
            retryProvider,
            loggerFactory,
          )
        )
      )

      adminHandler <- withGlobalLock[Unit, HttpValidatorAdminHandler](lock =>
        Future.successful(
          new HttpValidatorAdminHandler(
            automation,
            participantIdentitiesStore,
            validatorUserName = config.ledgerApiUser,
            validatorWalletUserName = config.validatorWalletUser,
            domainId = domainId,
            participantAdminConnection,
            lock,
            retryProvider = retryProvider,
            loggerFactory,
          )
        )
      )

      commonAdminHandler = new HttpAdminHandler(
        status
          .map(CNNodeStatus.fromNodeStatus)
          .map(NodeStatus.Success(_)),
        loggerFactory,
      )

      walletHandler = new HttpWalletHandler(
        walletManager,
        scanConnection,
        loggerFactory,
        retryProvider,
      )

      routes = cors(
        CorsSettings(ac).withAllowedMethods(
          List(
            HttpMethods.DELETE,
            HttpMethods.GET,
            HttpMethods.POST,
            HttpMethods.HEAD,
            HttpMethods.OPTIONS,
          )
        )
      ) {
        newTraceContext { traceContext =>
          requestLogger(traceContext) {
            HttpErrorHandler(loggerFactory)(traceContext) {
              concat(
                ValidatorResource.routes(
                  handler,
                  operationId =>
                    (operationId match {
                      // TODO(#5855) consider removing this once user onboarding doesn't require acquiring a global lock
                      case "register" => withRequestTimeout(60.seconds)
                      case _ => pass
                    }).tflatMap(_ =>
                      AuthExtractor(verifier, loggerFactory, "canton network validator realm")(
                        operationId
                      )
                    ),
                ),
                WalletResource.routes(
                  walletHandler,
                  AuthExtractor(verifier, loggerFactory, "canton network wallet realm"),
                ),
                ValidatorAdminResource.routes(
                  adminHandler,
                  operationId =>
                    (operationId match {
                      // TODO(#5855) consider removing this once user onboarding doesn't require acquiring a global lock
                      case "onboardUser" => withRequestTimeout(60.seconds)
                      case _ => pass
                    }).tflatMap(_ =>
                      if (config.enableAdminAuth) {
                        AdminAuthExtractor(
                          verifier,
                          validatorParty,
                          automation.connection,
                          loggerFactory,
                          "canton network validator operator realm",
                        )(operationId).tflatMap(_ => provide(()))
                      } else {
                        provide(())
                      }
                    ),
                ),
                CommonAdminResource.routes(commonAdminHandler, _ => provide(())),
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

  // Validator actually uploads packages so no dep.
  override lazy val requiredTemplates = Set.empty
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
}
