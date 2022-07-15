package com.daml.network.svc

import java.util.concurrent.ScheduledExecutorService
import akka.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either._
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.environment.CoinNodeBootstrapBase
import com.daml.network.examples.v0.SvcAppServiceGrpc
import com.daml.network.svc.admin.grpc.GrpcSvcAppService
import com.daml.network.svc.config.{LocalSvcAppConfig, SvcAppParameters}
import com.daml.network.svc.metrics.SvcAppMetrics
import com.daml.network.svc.store.SvcAppStore
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource._
import com.digitalasset.canton.time._

import scala.annotation.nowarn
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of an SVC app.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SvcAppBootstrap(
    override val name: InstanceName,
    val config: LocalSvcAppConfig,
    val svcNodeParameters: SvcAppParameters,
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
    @nowarn("cat=unused")
    executionSequencerFactory: ExecutionSequencerFactory,
) extends CoinNodeBootstrapBase[
      SvcAppNode,
      LocalSvcAppConfig,
      SvcAppParameters,
    ](
      name,
      config,
      svcNodeParameters,
      clock,
      metrics,
      storageFactory,
      parentLogger.append(SvcAppBootstrap.LoggerFactoryKeyName, name.unwrap),
    ) {

  override def initialize: EitherT[Future, String, Unit] = startInstanceUnlessClosing {
    EitherT.rightT[Future, String] {
      val svcStore = SvcAppStore(storage, loggerFactory)

      val connection =
        createLedgerConnection(config.remoteParticipant, svcNodeParameters.processingTimeouts)

      adminServerRegistry.addService(
        SvcAppServiceGrpc.bindService(
          new GrpcSvcAppService(connection, loggerFactory),
          executionContext,
        )
      )
      new SvcAppNode(config, svcNodeParameters, storage, svcStore, clock, loggerFactory)
    }
  }

  override def isActive: Boolean = storage.isActive
}

// TODO(Arne): Do we need this factory construction?
// I think Canton only needs this for being able to generalize a community/enterprise participant with the same method
// while being able to return an `Either` to signal that the initialization failed
object SvcAppBootstrap {
  val LoggerFactoryKeyName: String = "SVC"

  trait Factory[PC <: LocalSvcAppConfig] {
    def create(
        name: String,
        svcConfig: LocalSvcAppConfig,
        svcNodeParameters: SvcAppParameters,
        clock: Clock,
        testingTimeService: TestingTimeService,
        svcMetrics: SvcAppMetrics,
        testingConfig: TestingConfigInternal,
        futureSupervisor: FutureSupervisor,
        loggerFactory: NamedLoggerFactory,
    )(implicit
        executionContext: ExecutionContextIdlenessExecutorService,
        scheduler: ScheduledExecutorService,
        actorSystem: ActorSystem,
        executionSequencerFactory: ExecutionSequencerFactory,
    ): Either[String, SvcAppBootstrap]
  }

  object SvcAppFactory extends Factory[LocalSvcAppConfig] {

    override def create(
        name: String,
        svcConfig: LocalSvcAppConfig,
        svcNodeParameters: SvcAppParameters,
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
            svcNodeParameters,
            testingConfigInternal,
            clock,
            svcMetrics,
            new CommunityStorageFactory(svcConfig.storage),
            loggerFactory,
          )
        )
        .leftMap(_.toString)
  }
}
