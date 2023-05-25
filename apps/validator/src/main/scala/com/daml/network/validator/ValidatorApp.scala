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
import com.daml.network.auth.{AuthConfig, AuthExtractor, HMACVerifier, RSAVerifier}
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.config.{NetworkAppClientConfig, SharedCNNodeAppParameters}
import com.daml.network.environment.{CNLedgerClient, CNLedgerConnection, CNNode, CNNodeStatus}
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
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.admin.http.HttpWalletHandler
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TracerProvider
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

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
    )
    with BasicDirectives {

  private def getSvcPartyId(scanConnection: ScanConnection): Future[PartyId] =
    retryProvider
      .retryForAutomation(
        "getSvcPartyId",
        scanConnection.getSvcPartyId(),
        logger,
      )

  private def setupWalletDars(connection: CNLedgerConnection): Future[Unit] = {
    logger.info(s"Attempting to setup wallet...")
    for {
      _ <- connection.uploadDarFile(new UploadablePackage {
        // should be the same as package dependency in wallet app
        lazy val packageId: String =
          installCodegen.WalletAppInstall.TEMPLATE_ID.getPackageId

        // See `Compile / resourceGenerators` in build.sbt
        lazy val resourcePath: String = "dar/wallet-0.1.0.dar"
      })
      _ <-
        if (config.enableCoinRulesUpgrade) {
          logger.info("Upgrades enabled on this validator, uploading also cc v1test")
          connection.uploadDarFile(new UploadablePackage {
            // should be the same as package dependency in wallet app
            lazy val packageId: String =
              ccV1Test.coin.CoinRulesV1Test.COMPANION.TEMPLATE_ID.getPackageId

            // See `Compile / resourceGenerators` in build.sbt
            lazy val resourcePath: String = "dar/canton-coin-0.1.1.dar"
          })
        } else Future.successful(())
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
      domainId: DomainId,
  ): Future[Unit] = {
    logger.info(s"Attempting to setup app $name...")
    for {
      _ <- instance.dars.traverse_(dar => storeWithIngestion.connection.uploadDarFile(dar))
      party <- storeWithIngestion.connection.getOrAllocateParty(
        instance.serviceUser,
        Seq(new User.Right.CanReadAs(validatorParty.toProtoPrimitive)),
      )
      _ <- ValidatorUtil
        .onboard(
          instance.walletUser.getOrElse(instance.serviceUser),
          Some(party),
          storeWithIngestion,
          validatorUserName = config.ledgerApiUser,
          domainId,
          retryProvider,
          logger,
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
    logger.info("Waiting for ValidatorLicense contract to become visible")
    retryProvider.retryForAutomation(
      "Wait for ValidatorLicense",
      for {
        validatorLicenseResult <- store.lookupValidatorLicenseWithOffset()
        _ <- validatorLicenseResult match {
          case QueryResult(_, Some(_)) =>
            logger.info("ValidatorLicense found, done waiting")
            Future.successful(())
          case _ =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(s"ValidatorLicense contract not found yet")
            )
        }
      } yield (),
      logger,
    )
  }

  private def requestOnboarding(
      svConfig: NetworkAppClientConfig,
      validatorParty: PartyId,
      secret: String,
  ): Future[Unit] = {
    logger.info(s"Requesting to be onboarded by SV at: ${svConfig.url}")
    retryProvider.retryForAutomation(
      "request onboarding",
      SvConnection(
        svConfig,
        retryProvider,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      ).flatMap { svConnection =>
        svConnection
          .onboardValidator(validatorParty, secret)
          .andThen(_ => svConnection.close())
      },
      logger,
    )
  }

  override def initialize(
      ledgerClient: CNLedgerClient,
      validatorParty: PartyId,
  ): Future[ValidatorApp.State] =
    for {
      scanConnection <- ScanConnection(
        ledgerClient,
        config.scanClient,
        clock,
        retryProvider,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      )
      svcParty <- getSvcPartyId(scanConnection)
      _ <- setupWalletDars(ledgerClient.connection(this.getClass.getSimpleName, loggerFactory))
      key = ValidatorStore.Key(
        validatorParty = validatorParty,
        svcParty = svcParty,
      )
      store = ValidatorStore(
        key,
        storage,
        config.domains,
        loggerFactory,
        futureSupervisor,
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
          timeouts,
          futureSupervisor,
        )
      automation = new ValidatorAutomationService(
        config.automation,
        config.domains.global.buyExtraTraffic,
        clock,
        walletManager,
        store,
        scanConnection,
        ledgerClient,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      domainId <- waitForDomainConnection(store.domains, config.domains.global.alias)
      _ <- waitForAcsIngestion(store.multiDomainAcsStore, domainId)
      _ <- config.appInstances.toList.traverse({ case (name, instance) =>
        setupAppInstance(
          name,
          instance,
          validatorParty,
          automation,
          domainId,
        )
      })
      _ <- ValidatorUtil.onboard(
        endUserName = config.validatorWalletUser.getOrElse(config.ledgerApiUser),
        knownParty = Some(validatorParty),
        automation,
        validatorUserName = config.ledgerApiUser,
        domainId,
        retryProvider,
        logger,
      )
      _ <- ensureOnboarded(store, validatorParty, config.onboarding)
      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl) => new RSAVerifier(audience, jwksUrl)
      }

      handler = new HttpValidatorHandler(
        automation,
        validatorUserName = config.ledgerApiUser,
        domainId = domainId,
        retryProvider = retryProvider,
        loggerFactory,
      )

      adminHandler = new HttpValidatorAdminHandler(
        automation,
        validatorUserName = config.ledgerApiUser,
        domainId = domainId,
        retryProvider = retryProvider,
        loggerFactory,
      )

      // TODO(#3467) -- attach handler before app initialization, i.e. in bootstrap
      commonAdminHandler = new HttpAdminHandler(
        status
          .map(CNNodeStatus.fromNodeStatus)
          .map(NodeStatus.Success(_)),
        loggerFactory,
      )

      walletHandler = new HttpWalletHandler(
        walletManager,
        clock,
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
                  AuthExtractor(verifier, loggerFactory, "canton network validator realm"),
                ),
                WalletResource.routes(
                  walletHandler,
                  AuthExtractor(verifier, loggerFactory, "canton network wallet realm"),
                ),
                ValidatorAdminResource.routes(
                  adminHandler,
                  _ => provide(()),
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

    } yield {
      ValidatorApp.State(
        scanConnection,
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
      )(logger)
  }
}
