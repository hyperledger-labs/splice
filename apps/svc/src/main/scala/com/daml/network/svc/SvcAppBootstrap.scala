package com.daml.network.svc

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.svc.admin.SvcAutomationService
import com.daml.network.svc.admin.grpc.GrpcSvcAppService
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.svc.metrics.SvcAppMetrics
import com.daml.network.svc.store.SvcAppStore
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._

import java.util.concurrent.ScheduledExecutorService
import scala.annotation.nowarn
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of an SVC app.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SvcAppBootstrap(
    override val name: InstanceName,
    val config: LocalSvcAppConfig,
    val svcAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: SvcAppMetrics,
    storageFactory: StorageFactory,
    parentLogger: NamedLoggerFactory,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    @nowarn("cat=unused")
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      SvcAppNode,
      LocalSvcAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      svcAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(SvcAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    val svcStore = SvcAppStore(storage, loggerFactory)

    val ledgerClient =
      createLedgerClient(config.remoteParticipant, svcAppParameters.processingTimeouts)

    val service = new GrpcSvcAppService(
      ledgerClient,
      config.damlUser,
      loggerFactory,
    )

    adminServerRegistry.addService(
      SvcServiceGrpc.bindService(
        service,
        executionContext,
      )
    )

    val connection = ledgerClient.connection("SvcAppBootstrap")

    val svcApp = for {
      svcPartyId <- connection.getOrAllocateParty(config.damlUser)
      _ = logger.info(s"Allocated SVC party $svcPartyId")
      _ <- connection.uploadDarFile(CoinUtil) // TODO(i353) move away from dar upload during init
      _ <- CoinUtil.setupApp(svcPartyId, connection)
      _ = logger.info(s"SVC App is initialized")
      automation = new SvcAutomationService(
        svcPartyId,
        ledgerClient,
        loggerFactory,
        timeouts,
        svcStore,
      )
    } yield {
      new SvcAppNode(
        config,
        svcAppParameters,
        storage,
        automation,
        svcStore,
        clock,
        loggerFactory,
      )
    }

    // TODO(i447): more robust retry + finding out where exceptions (e.g. io.grpc.StatusRuntimeException) disappear too
    EitherT(
      svcApp
        .recover { err =>
          logger.error(s"SVC initialization failed with $err")
          sys.exit(1)
        }
        .map(Right(_)): Future[Either[String, SvcAppNode]]
    )
  }

  override def isActive: Boolean = storage.isActive
}

object SvcAppBootstrap {
  val LoggerFactoryKeyName: String = "SVC"

  def apply(
      name: String,
      svcConfig: LocalSvcAppConfig,
      svcAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      svcMetrics: SvcAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, SvcAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new SvcAppBootstrap(
          _,
          svcConfig,
          svcAppParameters,
          testingConfigInternal,
          clock,
          svcMetrics,
          new CommunityStorageFactory(svcConfig.storage),
          loggerFactory,
        )
      )
      .leftMap(_.toString)
}
