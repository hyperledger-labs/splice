package com.daml.network.validator

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.*
import cats.implicits.*
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.User
import com.daml.network.admin.http.{HttpAdminHandler, HttpErrorHandler}
import com.daml.network.admin.api.TraceContextDirectives.newTraceContext
import com.daml.network.auth.{AuthConfig, AuthExtractor, HMACVerifier, RSAVerifier}
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.config.{CNHttpClientConfig, SharedCNNodeAppParameters}
import com.daml.network.environment.{CNLedgerClient, CNLedgerConnection, CNNode, CNNodeStatus}
import com.daml.network.http.v0.validator.ValidatorResource
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.util.{HasHealth, UploadablePackage}
import com.daml.network.validator.admin.http.HttpValidatorHandler
import com.daml.network.validator.admin.http.HttpValidatorAdminHandler
import com.daml.network.validator.automation.ValidatorAutomationService
import com.daml.network.validator.config.{
  AppInstance,
  ValidatorAppBackendConfig,
  ValidatorOnboardingConfig,
}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
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
import com.daml.network.http.v0.validatorAdmin.ValidatorAdminResource
import akka.http.scaladsl.server.directives.BasicDirectives
import com.daml.network.http.v0.commonAdmin.CommonAdminResource

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
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    )
    with BasicDirectives {

  private def getSvcPartyId(ledgerClient: CNLedgerClient): Future[PartyId] = {
    // Via scan
    for {
      scanConnection <- ScanConnection(
        ledgerClient,
        config.remoteScan,
        clock,
        retryProvider,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      )

      partyId <- retryProvider
        .retryForAutomation(
          "getSvcPartyId",
          scanConnection.getSvcPartyId(),
          logger,
        )
        .andThen(_ => scanConnection.close())

    } yield partyId
  }

  private def setupWallet(connection: CNLedgerConnection): Future[(PartyId, String)] = {
    logger.info(s"Attempting to setup wallet...")
    for {
      _ <- connection.uploadDarFile(new UploadablePackage {
        // should be the same as package dependency in wallet app
        lazy val packageId: String =
          installCodegen.WalletAppInstall.TEMPLATE_ID.getPackageId

        // See `Compile / resourceGenerators` in build.sbt
        lazy val resourcePath: String = "dar/wallet-0.1.0.dar"
      })
      party <- connection.getOrAllocateParty(config.walletServiceUser)
      // Note: need to immediately grant right to act as wallet service user in order to install wallet install contract
      // TODO(#713): remove this workaround for missing act-as-any-party rights
      _ <- connection.grantUserRights(config.ledgerApiUser, Seq(party), Seq.empty)
    } yield {
      logger.info(
        s"Setup wallet with service user ${config.walletServiceUser} and primary party $party"
      )
      (party, config.walletServiceUser)
    }
  }

  private def setupAppInstance(
      connection: CNLedgerConnection,
      name: String,
      instance: AppInstance,
      validatorParty: PartyId,
      store: ValidatorStore,
      walletServiceUser: String,
      domainId: DomainId,
  ): Future[Unit] = {
    logger.info(s"Attempting to setup app $name...")
    for {
      _ <- instance.dars.traverse_(dar => connection.uploadDarFile(dar))
      party <- connection.getOrAllocateParty(
        instance.serviceUser,
        Seq(new User.Right.CanReadAs(validatorParty.toProtoPrimitive)),
      )
      _ <- ValidatorUtil
        .onboard(
          instance.walletUser.getOrElse(instance.serviceUser),
          Some(party),
          connection,
          store,
          validatorUserName = config.ledgerApiUser,
          walletServiceUser,
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
              _ <- requestOnboarding(oc.remoteSv.adminApi, validatorParty, oc.secret)
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
      svConfig: CNHttpClientConfig,
      validatorParty: PartyId,
      secret: String,
  ): Future[Unit] = {
    logger.info(s"Requesting to be onboarded by SV at: ${svConfig.url}")
    retryProvider.retryForAutomation(
      "request onboarding", {
        val svConnection = new SvConnection(
          svConfig,
          retryProvider,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )
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
      svcParty <- getSvcPartyId(ledgerClient)
      connection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
      (walletServiceParty, walletServiceUser) <- setupWallet(connection)
      key = ValidatorStore.Key(
        validatorParty = validatorParty,
        svcParty = svcParty,
        walletServiceParty = walletServiceParty,
      )
      store = ValidatorStore(
        key,
        storage,
        config.domains,
        loggerFactory,
        futureSupervisor,
        retryProvider,
      )
      automation = new ValidatorAutomationService(
        config.automation,
        clock,
        store,
        ledgerClient,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      domainId <- waitForDomainConnection(store.domains, config.domains.global)
      _ <- waitForAcsIngestion(store.multiDomainAcsStore, domainId)
      _ <- config.appInstances.toList.traverse({ case (name, instance) =>
        setupAppInstance(
          connection,
          name,
          instance,
          validatorParty,
          store,
          walletServiceUser,
          domainId,
        )
      })
      _ <- ValidatorUtil.onboard(
        endUserName = config.validatorWalletUser.getOrElse(config.ledgerApiUser),
        knownParty = Some(validatorParty),
        connection,
        store,
        validatorUserName = config.ledgerApiUser,
        walletServiceUser,
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
        ledgerClient,
        store,
        validatorUserName = config.ledgerApiUser,
        walletServiceUser = walletServiceUser,
        domainId = domainId,
        retryProvider = retryProvider,
        loggerFactory,
      )

      adminHandler = new HttpValidatorAdminHandler(
        ledgerClient,
        store,
        validatorUserName = config.ledgerApiUser,
        walletServiceUser = walletServiceUser,
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

      routes = cors() {
        newTraceContext { traceContext =>
          requestLogger(traceContext) {
            HttpErrorHandler(loggerFactory)(traceContext) {
              concat(
                ValidatorResource.routes(
                  handler,
                  AuthExtractor(verifier, loggerFactory, "canton network validator realm"),
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
        storage,
        store,
        automation,
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
      storage: Storage,
      store: ValidatorStore,
      automation: ValidatorAutomationService,
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
        store,
        storage,
      )(logger)
  }
}
