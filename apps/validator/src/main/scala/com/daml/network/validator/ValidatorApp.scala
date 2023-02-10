package com.daml.network.validator

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import cats.implicits.*
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.User
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.auth.{AuthConfig, AuthExtractor, HMACVerifier, RSAVerifier}
import com.daml.network.codegen.java.cc.coin.CoinRulesRequest
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.config.{CoinHttpClientConfig, SharedCoinAppParameters}
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNode, CoinRetries}
import com.daml.network.http.v0.validator.ValidatorResource
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.util.{HasHealth, UploadablePackage}
import com.daml.network.validator.admin.http.HttpValidatorHandler
import com.daml.network.validator.automation.ValidatorAutomationService
import com.daml.network.validator.config.{
  AppInstance,
  ValidatorAppBackendConfig,
  ValidatorOnboardingConfig,
}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TracerProvider
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

/** Class representing a Validator app instance. */
class ValidatorApp(
    override val name: InstanceName,
    val config: ValidatorAppBackendConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    retryProvider: CoinRetries,
    futureSupervisor: FutureSupervisor,
)(implicit
    ac: ActorSystem,
    esf: ExecutionSequencerFactory,
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends CoinNode[ValidatorApp.State](
      config.ledgerApiUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      retryProvider,
    ) {

  private def setupWallet(connection: CoinLedgerConnection): Future[(PartyId, String)] = {
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
      connection: CoinLedgerConnection,
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
          flagCloseable = this,
          logger,
        )
    } yield {
      logger.info(
        s"Setup app $name with service user ${instance.serviceUser}, wallet user ${instance.walletUser}  primary party $party, and uploaded ${instance.dars}."
      )
    }
  }

  private def waitForCoinRules(
      connection: CoinLedgerConnection,
      store: ValidatorStore,
      svcParty: PartyId,
      validatorParty: PartyId,
  ): Future[Unit] = {
    logger.info("Waiting for CoinRules contract to become visible")
    retryProvider.retryForAutomationGrpc(
      "Wait for CoinRules",
      for {
        coinRulesResult <- store.lookupCoinRulesWithOffset()
        _ <- coinRulesResult match {
          case QueryResult(_, Some(_)) =>
            logger.info("CoinRules found, done waiting")
            Future.successful(())
          case _ =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(s"CoinRules contract not found yet")
            )
        }
      } yield (),
      this,
    )
  }

  private def ensureOnboarded(
      connection: CoinLedgerConnection,
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
              _ <- waitForValidatorLicense(connection, store)
            } yield ()
          case None => sys.error("Not onboarded but no onboarding config found; exiting.")
        }
    }
  }

  private def waitForValidatorLicense(
      connection: CoinLedgerConnection,
      store: ValidatorStore,
  ): Future[Unit] = {
    logger.info("Waiting for ValidatorLicense contract to become visible")
    retryProvider.retryForAutomationGrpc(
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
      this,
    )
  }

  private def requestOnboarding(
      svConfig: CoinHttpClientConfig,
      validatorParty: PartyId,
      secret: String,
  ): Future[Unit] = {
    logger.info(s"Requesting to be onboarded by SV at: ${svConfig.url}")
    retryProvider.retryForAutomationHttp(
      "request onboarding", {
        val svConnection = new SvConnection(
          svConfig,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )
        svConnection
          .onboardValidator(validatorParty, secret)
          .andThen(_ => svConnection.close())
      },
      this,
    )
  }

  private def createCoinRulesRequest(
      connection: CoinLedgerConnection,
      store: ValidatorStore,
      svcParty: PartyId,
      validatorParty: PartyId,
      domainId: DomainId,
  ): Future[Unit] = {
    logger.info("Attempting to create CoinRulesRequest")
    val coinRulesReq =
      new CoinRulesRequest(validatorParty.toProtoPrimitive, svcParty.toProtoPrimitive)
    retryProvider
      .retryForAutomationGrpc(
        "createCoinRulesRequest",
        for {
          coinRulesResult <- store.lookupCoinRulesWithOffset()
          coinRulesRequestResult <- store.lookupCoinRulesRequestWithOffset()
          _ <- (coinRulesResult, coinRulesRequestResult) match {
            case (QueryResult(off1, None), QueryResult(off2, None)) =>
              connection
                .submitCommands(
                  actAs = Seq(validatorParty),
                  readAs = Seq.empty,
                  commands = coinRulesReq.create.commands.asScala.toSeq,
                  commandId = CoinLedgerConnection
                    .CommandId(
                      "com.daml.network.validator.createCoinRulesRequest",
                      Seq(validatorParty),
                    ),
                  deduplicationOffset = Ordering.String.min(off1, off2),
                  domainId = domainId,
                )
            case (QueryResult(_, Some(_)), _) =>
              logger.info("CoinRulesRequest already exists, skipping")
              Future.successful(())
            case (_, QueryResult(_, Some(_))) =>
              logger.info("CoinRules already exists, skipping")
              Future.successful(())
          }
        } yield (),
        this,
      )
      .map(_ => logger.info("Created CoinRulesRequest"))
  }

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      validatorParty: PartyId,
  ): Future[ValidatorApp.State] =
    for {
      scanConnection <-
        Future.successful(
          new ScanConnection(
            config.remoteScan.adminApi,
            coinAppParameters.processingTimeouts,
            loggerFactory,
          )
        )
      connection = ledgerClient.connection()
      svcParty <- retryProvider.retryForAutomationHttp(
        "getSvcPartyId",
        scanConnection.getSvcPartyId(),
        this,
      )
      (walletServiceParty, walletServiceUser) <- setupWallet(connection)
      key = ValidatorStore.Key(
        validatorParty = validatorParty,
        svcParty = svcParty,
        walletServiceParty = walletServiceParty,
      )
      store = ValidatorStore(key, storage, config.domains, loggerFactory, futureSupervisor)
      automation = new ValidatorAutomationService(
        config.automation,
        clock,
        store,
        ledgerClient,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      _ <- store.domains.signalWhenConnected(config.domains.global)
      domainId <- store.domains.getDomainId(config.domains.global)
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
        flagCloseable = this,
        logger,
      )
      _ <- // TODO(#2241) activate this for all nodes
        if (config.onboarding.isDefined) {
          ensureOnboarded(connection, store, validatorParty, config.onboarding)
        } else {
          createCoinRulesRequest(connection, store, svcParty, validatorParty, domainId)
        }
      _ <- waitForCoinRules(connection, store, svcParty, validatorParty)
      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl) => new RSAVerifier(audience, jwksUrl)
      }

      routes = cors() {
        ValidatorResource.routes(
          new HttpValidatorHandler(
            ledgerClient,
            store,
            validatorUserName = config.ledgerApiUser,
            walletServiceUser = walletServiceUser,
            domainId = domainId,
            retryProvider = retryProvider,
            flagCloseable = this,
            loggerFactory,
          ),
          AuthExtractor(verifier, loggerFactory, "canton network validator realm"),
        )
      }
      httpConfig = config.adminApi.clientConfig.copy(
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
    } yield {
      ValidatorApp.State(
        storage,
        store,
        automation,
        scanConnection,
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
      scanConnection: ScanConnection,
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
        scanConnection,
        storage,
      )(logger)
  }
}
