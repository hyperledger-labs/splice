package com.daml.network.scan

import java.util.concurrent.ScheduledExecutorService

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.scan.admin.ScanAutomationService
import com.daml.network.scan.admin.grpc.GrpcScanService
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.scan.metrics.ScanAppMetrics
import com.daml.network.scan.store.ScanTransferStore
import com.daml.network.scan.v0.ScanServiceGrpc
import com.daml.network.util.Proto
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import com.digitalasset.canton.util.retry.{Backoff, Forever, Success}
import com.google.protobuf.empty.Empty

import scala.annotation.nowarn
import scala.concurrent.Future
import scala.concurrent.duration._

/** Class used to orchester the starting/initialization of Scan apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ScanAppBootstrap(
    override val name: InstanceName,
    val config: LocalScanAppConfig,
    val scanAppParameters: SharedCoinAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: ScanAppMetrics,
    storageFactory: StorageFactory,
    parentLogger: NamedLoggerFactory,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    @nowarn("cat=unused")
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      ScanApp,
      LocalScanAppConfig,
      SharedCoinAppParameters,
    ](
      name,
      config,
      scanAppParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(ScanAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    val transferStore = ScanTransferStore(storage, loggerFactory)
    val connection =
      createLedgerConnection(
        config.remoteParticipant,
        scanAppParameters.processingTimeouts,
      )

    val scanServiceGrpc = new GrpcScanService(connection, config.svcUser, loggerFactory)
    adminServerRegistry.addService(
      ScanServiceGrpc.bindService(
        scanServiceGrpc,
        executionContext,
      )
    )
    implicit val success: Success[Any] = Success.always
    val policy = Backoff(
      logger,
      this,
      Forever,
      1.seconds,
      10.seconds,
      s"Get SVC party within ScanApp $name initialization",
    )
    val scanApp = for {
      svcParty <- policy(scanServiceGrpc.getSvcPartyId(Empty()), AllExnRetryable).map(resp =>
        Proto.tryDecode(Proto.Party)(resp.svcPartyId)
      )
      scanAutomationService = new ScanAutomationService(
        svcParty,
        config.remoteParticipant,
        loggerFactory,
        tracerProvider,
        timeouts,
        transferStore,
      )
    } yield new ScanApp(
      config,
      scanAppParameters,
      storage,
      scanAutomationService,
      transferStore,
      clock,
      loggerFactory,
    )
    EitherT(scanApp.map(Right(_)): Future[Either[String, ScanApp]])
  }

  override def isActive: Boolean = storage.isActive
}

object ScanAppBootstrap {
  val LoggerFactoryKeyName: String = "scan"

  def apply(
      name: String,
      scanConfig: LocalScanAppConfig,
      coinAppParameters: SharedCoinAppParameters,
      clock: Clock,
      testingTimeService: TestingTimeService,
      scanMetrics: ScanAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, ScanAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new ScanAppBootstrap(
          _,
          scanConfig,
          coinAppParameters,
          testingConfigInternal,
          clock,
          scanMetrics,
          new CommunityStorageFactory(scanConfig.storage),
          loggerFactory,
        )
      )
      .leftMap(_.toString)
}
