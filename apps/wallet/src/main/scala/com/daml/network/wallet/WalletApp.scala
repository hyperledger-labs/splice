package com.daml.network.wallet

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.Materializer
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{ContractCompanion, ContractId}
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.auth.*
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinNode, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{HasHealth, TemplateJsonDecoder}
import com.daml.network.validator.admin.api.client.ValidatorConnection
import com.daml.network.wallet.admin.grpc.GrpcWalletService
import com.daml.network.wallet.automation.WalletAutomationService
import com.daml.network.wallet.config.WalletAppBackendConfig
import com.daml.network.wallet.store.WalletStore
import com.daml.network.wallet.v0.WalletServiceGrpc
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.circe.Json
import io.grpc.ServerInterceptors
import io.opentelemetry.api.trace.Tracer
import org.slf4j.event.Level

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
    adminServerRegistry: CantonMutableHandlerRegistry,
    retryProvider: CoinRetries,
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

  private val httpExt = Http()(ac)
  implicit val httpClient: HttpRequest => Future[HttpResponse] = (req: HttpRequest) =>
    httpExt.singleRequest(req)

  implicit val placeholderTemplateJsonDecoder = new TemplateJsonDecoder() {
    override def decodeTemplate[TCid <: ContractId[T], T <: Template](
        companion: ContractCompanion[_, TCid, T]
    )(json: Json): T = throw new UnsupportedOperationException(
      "Placeholder template json decoder cannot decode templates"
    )
  }

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      walletServiceParty: PartyId,
  ): Future[WalletApp.State] = {
    for {
      scanConnection <- Future {
        new ScanConnection(
          config.remoteScan.adminApi,
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
      validatorUserInfo <- retryProvider.retryForAutomationHttp(
        "getValidatorUserInfo",
        validatorConnection.getValidatorUserInfo(),
        this,
      )
      svcParty <- retryProvider.retryForAutomationGrpc(
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
        WalletStore(walletStoreKey, storage, loggerFactory, coinAppParameters.processingTimeouts)
      walletManager =
        new UserWalletManager(
          ledgerClient,
          walletStore,
          config.automation,
          clock,
          config.treasury,
          storage: Storage,
          retryProvider,
          loggerFactory,
          timeouts,
        )
      automation = new WalletAutomationService(
        config.automation,
        clock,
        walletManager,
        ledgerClient,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      _ <- walletStore.acs.signalWhenIngested(OpenMiningRound.COMPANION)
    } yield {

      val verifier: SignatureVerifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl) => new RSAVerifier(audience, jwksUrl)
      }

      adminServerRegistry
        .addService(
          ServerInterceptors.intercept(
            WalletServiceGrpc.bindService(
              new GrpcWalletService(
                walletManager,
                ledgerClient,
                clock,
                loggerFactory,
                retryProvider,
              ),
              ec,
            ),
            new AuthInterceptor(
              verifier,
              loggerFactory,
            ),
          )
        )
        .discard

      WalletApp.State(
        automation,
        storage,
        walletStore,
        walletManager,
        scanConnection,
        validatorConnection,
        httpExt,
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
      http: HttpExt,
      timeouts: ProcessingTimeout,
      logger: TracedLogger,
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean =
      storage.isActive && automation.isHealthy && walletManager.isHealthy

    override def close(): Unit = {
      Lifecycle.close(
        automation,
        storage,
        walletStore,
        walletManager,
        scanConnection,
        validatorConnection,
        toCloseableHttpPools(http, logger, timeouts),
      )(logger)
    }
  }
  def toCloseableHttpPools(
      http: HttpExt,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  ): AutoCloseable =
    new AutoCloseable() {
      private val name = http.system.name
      override def close(): Unit = {
        implicit val loggingContext: ErrorLoggingContext =
          ErrorLoggingContext.fromTracedLogger(logger)(TraceContext.empty)
        timeouts.shutdownProcessing.await_(
          s"Http connection pools in Actor system ($name)",
          logFailing = Some(Level.WARN),
        )(
          http.shutdownAllConnectionPools()
        )
      }
      override def toString: String = s"Http connection pools in Actor system ($name)"
    }
}
