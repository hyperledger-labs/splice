package com.daml.network.validator

import akka.actor.ActorSystem
import cats.implicits.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.User
import com.daml.network.codegen.java.cc.coinrules.CoinRulesRequest
import com.daml.network.codegen.java.cn.wallet as walletCodegen
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNode, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AcsStore.QueryResult
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
import scala.jdk.CollectionConverters.*

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
    retryProvider: CoinRetries,
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
      retryProvider,
    ) {

  private def setupWallet(connection: CoinLedgerConnection): Future[(PartyId, String)] = {
    logger.info(s"Attempting to setup wallet...")
    for {
      _ <- connection.uploadDarFile(new UploadablePackage {
        lazy val packageId: String =
          walletCodegen.AppPaymentRequest.COMPANION.TEMPLATE_ID.getPackageId

        // See `Compile / resourceGenerators` in build.sbt
        lazy val resourcePath: String = "dar/wallet-0.1.0.dar"
      })
      party <- connection.getOrAllocateParty(config.walletServiceUser)
      // Note: need to immediately grant right to act as wallet service user in order to install wallet install contract
      // TODO(i713): remove this workaround for missing act-as-any-party rights
      _ <- connection.grantUserRights(config.damlUser, Seq(party), Seq.empty)
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
  ): Future[Unit] = {
    logger.info(s"Attempting to setup app $name...")
    for {
      _ <- instance.dars.traverse_(dar => connection.uploadDarFile(dar))
      party <- connection.getOrAllocateParty(
        instance.serviceUser,
        Seq(new User.Right.CanReadAs(validatorParty.toProtoPrimitive)),
      )
    } yield {
      logger.info(
        s"Setup app $name with service user ${instance.serviceUser},  primary party $party, and uploaded ${instance.dars}."
      )
    }
  }

  private def createWalletAppInstallAndValidatorRight(
      connection: CoinLedgerConnection,
      store: ValidatorStore,
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
      _ <- ValidatorUtil.installWalletForUser(
        validatorServiceParty = validatorParty,
        walletServiceParty = walletServiceParty,
        walletServiceUser = walletServiceUser,
        endUserName = validatorUser,
        endUserParty = validatorParty,
        svcParty = svcParty,
        connection = connection,
        store = store,
        retryProvider = retryProvider,
        flagCloseable = this,
        logger = logger,
      )
      _ <- CoinUtil.createValidatorRight(
        user = validatorParty,
        validator = validatorParty,
        svc = svcParty,
        connection = connection,
        lookupValidatorRightByParty = store.lookupValidatorRightByParty,
        retryProvider = retryProvider,
        flagCloseable = this,
        logger = logger,
      )
    } yield {
      logger.info(
        s"Created wallet install and validator right for validator party $validatorParty, svc $svcParty."
      )
    }
  }

  private def createCoinRulesRequest(
      connection: CoinLedgerConnection,
      store: ValidatorStore,
      svcParty: PartyId,
      validatorParty: PartyId,
  ): Future[Unit] = {
    logger.info("Attempting to create CoinRulesRequest")
    val coinRulesReq =
      new CoinRulesRequest(validatorParty.toProtoPrimitive, svcParty.toProtoPrimitive)
    retryProvider
      .retryForAutomationWithUncleanShutdown(
        "Create CoinRulesRequest",
        for {
          coinRulesResult <- store.lookupCoinRules()
          coinRulesRequestResult <- store.lookupCoinRulesRequest()
          _ <- (coinRulesResult, coinRulesRequestResult) match {
            case (QueryResult(off1, None), QueryResult(off2, None)) =>
              // TODO(#790) Switch to the generalized version of mkCommandId once it has been added
              val commandId = s"com.daml.network.validator.CoinRulesRequest_$validatorParty"
              connection
                .submitCommandsWithDedup(
                  actAs = Seq(validatorParty),
                  readAs = Seq.empty,
                  commands = coinRulesReq.create.commands.asScala.toSeq,
                  commandId = commandId,
                  deduplicationOffset = Ordering.String.min(off1, off2),
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
      svcParty <- retryProvider.retryForAutomationWithUncleanShutdown(
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
      store = ValidatorStore(key, storage, loggerFactory)
      automation = new ValidatorAutomationService(
        store,
        ledgerClient,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      _ <- config.appInstances.toList.traverse({ case (name, instance) =>
        setupAppInstance(connection, name, instance, validatorParty)
      })
      _ <- createWalletAppInstallAndValidatorRight(
        connection,
        store,
        svcParty = svcParty,
        validatorParty = validatorParty,
        validatorUser = config.damlUser,
        walletServiceParty = walletServiceParty,
        walletServiceUser = walletServiceUser,
      )
      _ <- createCoinRulesRequest(connection, store, svcParty, validatorParty)
    } yield {
      adminServerRegistry
        .addService(
          ValidatorAppServiceGrpc.bindService(
            new GrpcValidatorAppService(
              ledgerClient,
              store,
              config.damlUser,
              config.walletServiceUser,
              retryProvider,
              this,
              loggerFactory,
            ),
            ec,
          )
        )
        .discard
      ValidatorApp.State(
        storage,
        store,
        automation,
        scanConnection,
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
