package com.daml.network.validator

import akka.actor.ActorSystem
import cats.implicits._
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.codegen.CC.CoinRules.CoinRulesRequest
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNodeBootstrapBase}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.CoinUtil
import com.daml.network.validator.admin.grpc.GrpcValidatorAppService
import com.daml.network.validator.config.{AppInstance, LocalValidatorAppConfig}
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.daml.network.validator.store.ValidatorAppStore
import com.daml.network.validator.util.ValidatorUtil
import com.daml.network.validator.v0.ValidatorAppServiceGrpc
import com.daml.network.wallet.util.WalletUtil
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.EitherTUtil

import java.util.concurrent.ScheduledExecutorService
import scala.annotation.nowarn
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Validator node.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ValidatorAppBootstrap(
    override val name: InstanceName,
    val config: LocalValidatorAppConfig,
    val validatorAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: ValidatorAppMetrics,
    storageFactory: StorageFactory,
    parentLogger: NamedLoggerFactory,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    @nowarn("cat=unused")
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      ValidatorAppNode,
      LocalValidatorAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      validatorAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(ValidatorAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {

    val ledgerClient: CoinLedgerClient =
      createLedgerClient(config.remoteParticipant, validatorAppParameters.processingTimeouts)

    val scanConnection: ScanConnection =
      new ScanConnection(
        config.remoteScan.clientAdminApi,
        validatorAppParameters.processingTimeouts,
        loggerFactory,
      )
    val connection = ledgerClient.connection("ValidatorAppBootstrap")

    def setupWallet(): Future[PartyId] = {
      logger.info(s"Attempting to setup wallet...")
      for {
        _ <- connection.uploadDarFile(WalletUtil)
        party <- connection.getOrAllocateParty(config.walletServiceUser)
      } yield {
        logger.info(
          s"Setup wallet with service user ${config.walletServiceUser} and primary party $party"
        )
        party
      }
    }

    def setupAppInstance(name: String, instance: AppInstance): Future[Unit] = {
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

    def createRulesRequestAndUserHostedAtContracts(
        svcParty: PartyId,
        validatorParty: PartyId,
    ): Future[Unit] = {
      val coinRulesReq = CoinRulesRequest(user = validatorParty.toPrim, svc = svcParty.toPrim)
      connection
        .submitCommand(
          actAs = Seq(validatorParty),
          readAs = Seq(validatorParty),
          command = Seq(coinRulesReq.create.command),
        )
        .flatMap { _ =>
          CoinUtil.ExplicitDisclosureWorkaround.recordUserHostedAt(
            validatorParty,
            validatorParty,
            connection,
          )
        }
        .map(_ => ())
    }

    def init() = for {
      validatorParty <- connection.retryLedgerApi(
        connection.getPrimaryParty(config.damlUser),
        CoinLedgerConnection.RetryOnUserManagementError,
      )
      _ = logger.info(s"Got primary party of validator user: $validatorParty")
      svcParty <- scanConnection.getSvcPartyId()
      walletServiceParty <- setupWallet()
      _ <- config.appInstances.toList.traverse({ case (name, instance) =>
        setupAppInstance(name, instance)
      })
      _ <- ValidatorUtil.createValidatorRight(
        user = validatorParty,
        validator = validatorParty,
        svc = svcParty,
        connection = connection,
      )
      _ <- createRulesRequestAndUserHostedAtContracts(svcParty, validatorParty)
    } yield {
      ValidatorAppStore(
        validatorParty = validatorParty,
        svcParty = svcParty,
        walletServiceParty = walletServiceParty,
        storage,
        loggerFactory,
      )
    }

    for {
      store <- EitherTUtil.fromFuture(init(), _.toString)
    } yield {
      adminServerRegistry.addService(
        ValidatorAppServiceGrpc.bindService(
          new GrpcValidatorAppService(
            ledgerClient,
            scanConnection,
            store,
            config.damlUser,
            config.walletServiceUser,
            loggerFactory,
          ),
          executionContext,
        )
      )
      new ValidatorAppNode(
        config,
        validatorAppParameters,
        storage,
        store,
        ledgerClient,
        scanConnection,
        clock,
        loggerFactory,
      )
    }
  }

  override def isActive: Boolean = storage.isActive
}

object ValidatorAppBootstrap {
  val LoggerFactoryKeyName: String = "validator"

  def apply(
      name: String,
      validatorConfig: LocalValidatorAppConfig,
      validatorAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      validatorMetrics: ValidatorAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, ValidatorAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new ValidatorAppBootstrap(
          _,
          validatorConfig,
          validatorAppParameters,
          testingConfigInternal,
          clock,
          validatorMetrics,
          new CommunityStorageFactory(validatorConfig.storage),
          loggerFactory,
        )
      )
      .leftMap(_.toString)
}
