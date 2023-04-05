package com.daml.network.wallet

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.model.HttpMethods
import akka.stream.Materializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.http.HttpAdminHandler
import com.daml.network.admin.api.TraceContextDirectives.newTraceContext
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.auth.*
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.{CNLedgerClient, CNNode, CNNodeStatus}
import com.daml.network.http.v0.commonAdmin.CommonAdminResource
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
import com.digitalasset.canton.health.admin.data.NodeStatus
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
    mat: Materializer,
    tracer: Tracer,
) extends CNNode[WalletApp.State](
      config.serviceUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  override def initialize(
      ledgerClient: CNLedgerClient,
      walletServiceParty: PartyId,
  ): Future[WalletApp.State] = {
    for {
      scanConnection <- ScanConnection(
        ledgerClient,
        config.remoteScan,
        clock,
        retryProvider,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      )
      validatorAuthToken <- AuthTokenSource.fromConfig(config.validatorAuth, loggerFactory).getToken
      validatorConnection <- Future {
        new ValidatorConnection(
          config.validator.adminApi,
          retryProvider,
          coinAppParameters.processingTimeouts,
          loggerFactory,
          validatorAuthToken,
        )
      }
      validatorUserInfo <- retryProvider.retryForAutomation(
        "getValidatorUserInfo",
        validatorConnection.getValidatorUserInfo(),
        logger,
      )
      svcParty <- retryProvider.retryForAutomation(
        "getSvcPartyId",
        scanConnection.getSvcPartyId(),
        logger,
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
          retryProvider,
        )
      walletManager =
        new UserWalletManager(
          ledgerClient,
          config.domains.global,
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
        retryProvider,
        loggerFactory,
        timeouts,
      )
      domainId <- waitForDomainConnection(walletStore.domains, config.domains.global)
      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl) => new RSAVerifier(audience, jwksUrl)
      }
      handler = new HttpWalletHandler(
        walletManager,
        ledgerClient,
        clock,
        scanConnection,
        loggerFactory,
        retryProvider,
      )

      // TODO(#3467) -- attach handler before app initialization, i.e. in bootstrap
      adminHandler = new HttpAdminHandler(
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
            HttpMethods.HEAD,
            HttpMethods.OPTIONS,
          )
        )
      ) {
        newTraceContext { traceContext =>
          requestLogger(traceContext) {
            HttpErrorHandler(loggerFactory)(traceContext) {
              concat(
                WalletResource.routes(
                  handler,
                  AuthExtractor(verifier, loggerFactory, "canton network wallet realm"),
                ),
                CommonAdminResource.routes(adminHandler),
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
