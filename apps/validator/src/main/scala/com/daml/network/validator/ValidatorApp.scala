package com.daml.network.validator

import akka.actor.ActorSystem
import cats.implicits._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.codegen.CC.CoinRules.CoinRulesRequest
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNode}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{CoinUtil, UploadablePackage}
import com.daml.network.validator.admin.grpc.GrpcValidatorAppService
import com.daml.network.validator.automation.ValidatorAutomationService
import com.daml.network.validator.config.{AppInstance, LocalValidatorAppConfig}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.daml.network.validator.v0.ValidatorAppServiceGrpc
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a Validator app instance. */
class ValidatorApp(
    override val name: InstanceName,
    val config: LocalValidatorAppConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    adminServerRegistry: CantonMutableHandlerRegistry,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CoinNode[ValidatorApp.State](
      config.damlUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  private def setupWallet(connection: CoinLedgerConnection): Future[(PartyId, String)] = {
    logger.info(s"Attempting to setup wallet...")
    for {
      _ <- connection.uploadDarFile(new UploadablePackage {
        lazy val walletTemplateId: com.daml.ledger.api.v1.value.Identifier =
          ApiTypes.TemplateId.unwrap(walletCodegen.AppPaymentRequest.id)

        lazy val packageId: String = walletTemplateId.packageId

        // See `Compile / resourceGenerators` in build.sbt
        lazy val resourcePath: String = "dar/wallet-0.1.0.dar"
      })
      party <- connection.getOrAllocateParty(config.walletServiceUser)
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
  ): Future[Unit] = {
    logger.info(s"Attempting to setup app $name...")
    for {
      _ <- instance.dars.traverse_(dar => connection.uploadDarFile(dar))
      party <- connection.getOrAllocateParty(instance.serviceUser)
    } yield {
      logger.info(
        s"Setup app $name with service user ${instance.serviceUser},  primary party $party, and uploaded ${instance.dars}."
      )
    }
  }

  private def createWalletAppInstallAndValidatorRight(
      connection: CoinLedgerConnection,
      svcParty: PartyId,
      validatorParty: PartyId,
      validatorUser: String,
      walletServiceParty: PartyId,
      walletServiceUser: String,
  ): Future[Unit] = {
    logger.info(
      s"Attempting to create wallet install and validator right for validator party $validatorParty..."
    )
    for {
      _ <- retry(
        "install wallet for validator",
        ValidatorUtil.installWalletForUser(
          validatorServiceParty = validatorParty,
          walletServiceParty = walletServiceParty,
          walletServiceUser = walletServiceUser,
          endUserName = validatorUser,
          endUserParty = validatorParty,
          svcParty = svcParty,
          connection = connection,
          logger = logger,
        ),
      )
      _ <- retry(
        "create validator right",
        ValidatorUtil.createValidatorRight(
          user = validatorParty,
          validator = validatorParty,
          svc = svcParty,
          connection = connection,
        ),
      )
    } yield {
      logger.info(
        s"Created wallet install and validator right for validator party $validatorParty, svc $svcParty."
      )
    }
  }

  private def createRulesRequestAndUserHostedAtContracts(
      connection: CoinLedgerConnection,
      svcParty: PartyId,
      validatorParty: PartyId,
  ): Future[Unit] = {
    logger.info("Attempting to create rules request and userHostedAt.")
    val coinRulesReq = CoinRulesRequest(user = validatorParty.toPrim, svc = svcParty.toPrim)
    for {
      _ <- retry(
        "Create CoinRulesRequest",
        connection.ignoreDuplicateKeyErrors(
          connection
            .submitCommand(
              actAs = Seq(validatorParty),
              readAs = Seq(validatorParty),
              command = Seq(coinRulesReq.create.command),
            ),
          s"CoinRulesRequest($validatorParty, $svcParty)",
        ),
      )
      _ <- retry(
        "Create UserHostedAt for validator",
        CoinUtil.ExplicitDisclosureWorkaround.recordUserHostedAt(
          validatorParty,
          validatorParty,
          connection,
        ),
      )
    } yield {
      logger.info("Created rules request and userHostedAt.")
    }
  }

  override def initialize(
      ledgerClient: CoinLedgerClient,
      validatorParty: PartyId,
  ): Future[ValidatorApp.State] =
    for {
      scanConnection <-
        Future.successful(
          new ScanConnection(
            config.remoteScan.clientAdminApi,
            coinAppParameters.processingTimeouts,
            loggerFactory,
          )
        )
      connection = ledgerClient.connection("ValidatorAppBootstrap")
      svcParty <- retry("getSvcPartyId", scanConnection.getSvcPartyId())
      (walletServiceParty, walletServiceUser) <- setupWallet(connection)
      _ <- config.appInstances.toList.traverse({ case (name, instance) =>
        setupAppInstance(connection, name, instance)
      })
      _ <- createWalletAppInstallAndValidatorRight(
        connection,
        svcParty = svcParty,
        validatorParty = validatorParty,
        validatorUser = config.damlUser,
        walletServiceParty = walletServiceParty,
        walletServiceUser = walletServiceUser,
      )
      _ <- createRulesRequestAndUserHostedAtContracts(connection, svcParty, validatorParty)
    } yield {
      val key = ValidatorStore.Key(
        validatorParty = validatorParty,
        svcParty = svcParty,
        walletServiceParty = walletServiceParty,
      )
      val store = ValidatorStore(key, storage, loggerFactory)
      adminServerRegistry
        .addService(
          ValidatorAppServiceGrpc.bindService(
            new GrpcValidatorAppService(
              ledgerClient,
              store,
              config.damlUser,
              config.walletServiceUser,
              loggerFactory,
            ),
            ec,
          )
        )
        .discard
      val automation = new ValidatorAutomationService(
        store,
        ledgerClient,
        this,
        loggerFactory,
        timeouts,
      )
      ValidatorApp.State(
        storage,
        store,
        automation,
        scanConnection,
        loggerFactory.getTracedLogger(ValidatorApp.State.getClass),
      )
    }

  override val ports = Map("admin" -> config.adminApi.port)

  // Validator actually uploads packages so no dep.
  override val requiredTemplates = Set.empty
}

object ValidatorApp {
  case class State(
      storage: Storage,
      store: ValidatorStore,
      automation: ValidatorAutomationService,
      scanConnection: ScanConnection,
      logger: TracedLogger,
  ) extends AutoCloseable {
    override def close() =
      Lifecycle.close(
        automation,
        store,
        scanConnection,
        storage,
      )(logger)

  }
}
