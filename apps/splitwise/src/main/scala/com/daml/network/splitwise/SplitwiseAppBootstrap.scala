package com.daml.network.splitwise

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.admin.grpc.GrpcSplitwiseService
import com.daml.network.splitwise.config.LocalSplitwiseAppConfig
import com.daml.network.splitwise.metrics.SplitwiseAppMetrics
import com.daml.network.splitwise.store.SplitwiseAppStore
import com.daml.network.splitwise.v0.SplitwiseServiceGrpc
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

/** Class used to orchester the starting/initialization of Splitwise apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SplitwiseAppBootstrap(
    override val name: InstanceName,
    val config: LocalSplitwiseAppConfig,
    val splitwiseAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: SplitwiseAppMetrics,
    storageFactory: StorageFactory,
    parentLogger: NamedLoggerFactory,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    @nowarn("cat=unused")
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      SplitwiseApp,
      LocalSplitwiseAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      splitwiseAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(SplitwiseAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.rightT[Future, String] {
      val dummyStore = SplitwiseAppStore(storage, loggerFactory)

      val ledgerClient =
        createLedgerClient(
          config.remoteParticipant,
          splitwiseAppParameters.processingTimeouts,
        )

      val scanConnection: ScanConnection =
        new ScanConnection(
          config.remoteScan.clientAdminApi,
          splitwiseAppParameters.processingTimeouts,
          loggerFactory,
        )

      adminServerRegistry.addService(
        SplitwiseServiceGrpc.bindService(
          new GrpcSplitwiseService(
            ledgerClient,
            scanConnection,
            config.damlUser,
            loggerFactory,
          ),
          executionContext,
        )
      )
      new SplitwiseApp(
        config,
        splitwiseAppParameters,
        storage,
        dummyStore,
        scanConnection,
        clock,
        loggerFactory,
      )
    }
  }

  override def isActive: Boolean = storage.isActive
}

object SplitwiseAppBootstrap {
  val LoggerFactoryKeyName: String = "splitwise"

  def apply(
      name: String,
      splitwiseConfig: LocalSplitwiseAppConfig,
      coinAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      splitwiseMetrics: SplitwiseAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, SplitwiseAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new SplitwiseAppBootstrap(
          _,
          splitwiseConfig,
          coinAppParameters,
          testingConfigInternal,
          clock,
          splitwiseMetrics,
          new CommunityStorageFactory(splitwiseConfig.storage),
          loggerFactory,
        )
      )
      .leftMap(_.toString)
}
