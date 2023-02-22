package com.daml.network.wallet

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods
import akka.stream.Materializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.auth.*
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode, CoinRetries}
import com.daml.network.http.v0.wallet.WalletResource
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.HasHealth
import com.daml.network.validator.admin.api.client.ValidatorConnection
import com.daml.network.wallet.admin.http.HttpWalletHandler
import com.daml.network.wallet.automation.WalletAutomationService
import com.daml.network.wallet.config.WalletAppBackendConfig
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Wallet app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class WalletApp(
    override val name: InstanceName,
    val config: WalletAppBackendConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    retryProvider: CoinRetries,
    futureSupervisor: FutureSupervisor,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    mat: Materializer,
    tracer: Tracer,
) extends CoinNode[WalletApp.State](
      config.serviceUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
      CoinRetries(loggerFactory),
    ) {

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      walletServiceParty: PartyId,
  ): Future[WalletApp.State] = {
    for {
      scanConnection <- Future {
        new ScanConnection(
          config.remoteScan.adminApi,
          clock,
          coinAppParameters.processingTimeouts,
          loggerFactory,
        )
      }
      validatorAuthToken <- AuthTokenSource.fromConfig(config.validatorAuth, loggerFactory).getToken
      validatorConnection <- Future {
        new ValidatorConnection(
          config.validator.adminApi,
          coinAppParameters.processingTimeouts,
          loggerFactory,
          new JwtCallCredential(validatorAuthToken.getOrElse("")),
        )
      }
      validatorUserInfo <- retryProvider.retryForAutomation(
        "getValidatorUserInfo",
        validatorConnection.getValidatorUserInfo(),
        this,
      )
      svcParty <- retryProvider.retryForAutomation(
        "getSvcPartyId",
        scanConnection.getSvcPartyId(),
        this,
      )
      walletStoreKey = WalletStore.Key(
        walletServiceParty = walletServiceParty,
        validatorParty = validatorUserInfo.primaryParty,
        validatorUserName = validatorUserInfo.userName,
        svcParty = svcParty,
      )
      walletStore =
        WalletStore(
          walletStoreKey,
          storage,
          config.domains.global,
          loggerFactory,
          coinAppParameters.processingTimeouts,
          futureSupervisor,
        )
      walletManager =
        new UserWalletManager(
          ledgerClient,
          config.domains.global,
          participantAdminConnection,
          walletStore,
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
      automation = new WalletAutomationService(
        config.automation,
        clock,
        walletManager,
        ledgerClient,
        config.domains.global,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      domainId <- waitForDomainConnection(walletStore.domains, config.domains.global)
      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl) => new RSAVerifier(audience, jwksUrl)
      }

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
        requestLogger {
          WalletResource.routes(
            new HttpWalletHandler(
              walletManager,
              ledgerClient,
              clock,
              scanConnection,
              loggerFactory,
              retryProvider,
            ),
            AuthExtractor(verifier, loggerFactory, "canton network wallet realm"),
          )
        }
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
      WalletApp.State(
        automation,
        storage,
        walletStore,
        walletManager,
        scanConnection,
        validatorConnection,
        binding,
        timeouts,
        loggerFactory.getTracedLogger(WalletApp.State.getClass),
      )
    }
  }

  override lazy val ports =
    Map("admin" -> config.adminApi.port)

  // should be the same as uploaded package in validator app
  override lazy val requiredTemplates = Set(installCodegen.WalletAppInstall.TEMPLATE_ID)
}

object WalletApp {
  case class State(
      automation: WalletAutomationService,
      storage: Storage,
      walletStore: WalletStore,
      walletManager: UserWalletManager,
      scanConnection: ScanConnection,
      validatorConnection: ValidatorConnection,
      binding: Http.ServerBinding,
      timeouts: ProcessingTimeout,
      logger: TracedLogger,
  )(implicit el: ErrorLoggingContext)
      extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean =
      storage.isActive && automation.isHealthy && walletManager.isHealthy

    override def close(): Unit = {
      Lifecycle.close(
        AsyncCloseable(
          "http binding",
          binding.terminate(timeouts.shutdownNetwork.asFiniteApproximation),
          timeouts.shutdownNetwork.unwrap,
        ),
        automation,
        storage,
        walletStore,
        walletManager,
        scanConnection,
        validatorConnection,
      )(logger)
    }
  }
}
