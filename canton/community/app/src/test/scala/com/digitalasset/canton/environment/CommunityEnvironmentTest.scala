// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.environment

import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.metrics.api.{HistogramInventory, MetricName}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.config.{CantonConfig, CommunityCantonEdition, TestingConfigInternal}
import com.digitalasset.canton.integration.ConfigTransforms
import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable, FutureUnlessShutdown}
import com.digitalasset.canton.participant.config.*
import com.digitalasset.canton.participant.metrics.{ParticipantHistograms, ParticipantMetrics}
import com.digitalasset.canton.participant.sync.SyncServiceError
import com.digitalasset.canton.participant.{
  CantonLedgerApiServerFactory,
  ParticipantNode,
  ParticipantNodeBootstrap,
  ParticipantNodeBootstrapFactory,
  ParticipantNodeParameters,
}
import com.digitalasset.canton.resource.CommunityDbMigrationsMetaFactory
import com.digitalasset.canton.synchronizer.mediator.{
  MediatorNodeBootstrap,
  MediatorNodeBootstrapFactory,
  MediatorNodeConfig,
  MediatorNodeParameters,
}
import com.digitalasset.canton.synchronizer.metrics.{MediatorMetrics, SequencerMetrics}
import com.digitalasset.canton.synchronizer.sequencer.config.{
  SequencerNodeConfig,
  SequencerNodeParameters,
}
import com.digitalasset.canton.synchronizer.sequencer.{
  SequencerNodeBootstrap,
  SequencerNodeBootstrapFactory,
}
import com.digitalasset.canton.time.TestingTimeService
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, ConfigStubs, HasExecutionContext}
import com.digitalasset.daml.lf.engine.Engine
import monocle.macros.syntax.lens.*
import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.{ExecutionContext, Future}

class CommunityEnvironmentTest extends AnyWordSpec with BaseTest with HasExecutionContext {
  // we don't care about any values of this config, so just mock
  lazy val participant1Config: ParticipantNodeConfig = ConfigStubs.participant
  lazy val participant2Config: ParticipantNodeConfig = ConfigStubs.participant

  lazy val sampleConfig: CantonConfig = CantonConfig(
    sequencers = Map(
      InstanceName.tryCreate("s1") -> ConfigStubs.sequencer,
      InstanceName.tryCreate("s2") -> ConfigStubs.sequencer,
    ),
    mediators = Map(
      InstanceName.tryCreate("m1") -> ConfigStubs.mediator,
      InstanceName.tryCreate("m2") -> ConfigStubs.mediator,
    ),
    participants = Map(
      InstanceName.tryCreate("p1") -> participant1Config,
      InstanceName.tryCreate("p2") -> participant2Config,
    ),
  )

  trait CallResult[A] {
    def get: A
  }

  trait TestEnvironment {
    def config: CantonConfig = sampleConfig

    private val createParticipantMock =
      mock[(String, ParticipantNodeConfig) => ParticipantNodeBootstrap]
    private val createSequencerMock =
      mock[(String, SequencerNodeConfig) => SequencerNodeBootstrap]
    private val createMediatorMock =
      mock[(String, MediatorNodeConfig) => MediatorNodeBootstrap]

    def mockSequencer: SequencerNodeBootstrap = {
      val sequencer = mock[SequencerNodeBootstrap]
      when(sequencer.start()).thenReturn(EitherT.pure[Future, String](()))
      when(sequencer.name).thenReturn(InstanceName.tryCreate("mockD"))
      sequencer
    }

    def mockMediator: MediatorNodeBootstrap = {
      val mediator = mock[MediatorNodeBootstrap]
      when(mediator.start()).thenReturn(EitherT.pure[Future, String](()))
      when(mediator.name).thenReturn(InstanceName.tryCreate("mockD"))
      mediator
    }

    def mockParticipantAndNode: (ParticipantNodeBootstrap, ParticipantNode) = {
      val bootstrap = mock[ParticipantNodeBootstrap]
      val node = mock[ParticipantNode]
      val metrics = new ParticipantMetrics(
        new ParticipantHistograms(MetricName("test"))(new HistogramInventory),
        new NoOpMetricsFactory,
      )
      val closeContext = CloseContext(mock[FlagCloseable])
      when(bootstrap.name).thenReturn(InstanceName.tryCreate("mockP"))
      when(bootstrap.start()).thenReturn(EitherT.pure[Future, String](()))
      when(bootstrap.getNode).thenReturn(Some(node))
      when(
        node.reconnectSynchronizersIgnoreFailures(any[Boolean])(
          any[TraceContext],
          any[ExecutionContext],
        )
      ).thenReturn(EitherT.pure[FutureUnlessShutdown, SyncServiceError](()))
      when(bootstrap.metrics).thenReturn(metrics)
      when(bootstrap.closeContext).thenReturn(closeContext)
      when(node.config).thenReturn(participant1Config)
      (bootstrap, node)
    }
    def mockParticipant: ParticipantNodeBootstrap = mockParticipantAndNode._1

    val environment = new CantonEnvironment(
      config,
      CommunityCantonEdition,
      TestingConfigInternal(initializeGlobalOpenTelemetry = false),
      new ParticipantNodeBootstrapFactory {
        override protected def createLedgerApiServerFactory(
            arguments: this.Arguments,
            engine: Engine,
            testingTimeService: TestingTimeService,
        )(implicit
            executionContext: ExecutionContextIdlenessExecutorService,
            actorSystem: ActorSystem,
        ): CantonLedgerApiServerFactory = mock[CantonLedgerApiServerFactory]

        override def create(
            arguments: NodeFactoryArguments[
              ParticipantNodeConfig,
              ParticipantNodeParameters,
              ParticipantMetrics,
            ],
            testingTimeService: TestingTimeService,
        )(implicit
            executionContext: ExecutionContextIdlenessExecutorService,
            scheduler: ScheduledExecutorService,
            actorSystem: ActorSystem,
            executionSequencerFactory: ExecutionSequencerFactory,
        ): Either[String, ParticipantNodeBootstrap] = Right(
          createParticipantMock(arguments.name, arguments.config)
        )
      },
      new SequencerNodeBootstrapFactory {

        override def create(
            arguments: NodeFactoryArguments[
              SequencerNodeConfig,
              SequencerNodeParameters,
              SequencerMetrics,
            ]
        )(implicit
            executionContext: ExecutionContextIdlenessExecutorService,
            scheduler: ScheduledExecutorService,
            actorSystem: ActorSystem,
        ): Either[String, SequencerNodeBootstrap] =
          Right(createSequencerMock(arguments.name, arguments.config))
      },
      new MediatorNodeBootstrapFactory {
        override def create(
            arguments: NodeFactoryArguments[
              MediatorNodeConfig,
              MediatorNodeParameters,
              MediatorMetrics,
            ]
        )(implicit
            executionContext: ExecutionContextIdlenessExecutorService,
            scheduler: ScheduledExecutorService,
            executionSequencerFactory: ExecutionSequencerFactory,
            actorSystem: ActorSystem,
        ): Either[String, MediatorNodeBootstrap] =
          Right(createMediatorMock(arguments.name, arguments.config))
      },
      new CommunityDbMigrationsMetaFactory(loggerFactory),
      loggerFactory,
    )

    protected def setupParticipantFactory(create: => ParticipantNodeBootstrap): Unit =
      setupParticipantFactoryInternal(anyString(), create)

    protected def setupParticipantFactory(id: String, create: => ParticipantNodeBootstrap): Unit =
      setupParticipantFactoryInternal(ArgumentMatchers.eq(id), create)

    private def setupParticipantFactoryInternal(
        idMatcher: => String,
        create: => ParticipantNodeBootstrap,
    ): Unit =
      when(createParticipantMock(idMatcher, any[ParticipantNodeConfig])).thenAnswer(create)

    protected def setupSequencerFactory(id: String, create: => SequencerNodeBootstrap): Unit =
      when(createSequencerMock(eqTo(id), any[SequencerNodeConfig])).thenAnswer(create)

    protected def setupMediatorFactory(id: String, create: => MediatorNodeBootstrap): Unit =
      when(createMediatorMock(eqTo(id), any[MediatorNodeConfig])).thenAnswer(create)
  }

  "Environment" when {
    "starting with startAndReconnect" should {
      "succeed normally" in new TestEnvironment {

        val pp = mockParticipant
        Seq("p1", "p2").foreach(setupParticipantFactory(_, pp))
        Seq("s1", "s2").foreach(setupSequencerFactory(_, mockSequencer))
        Seq("m1", "m2").foreach(setupMediatorFactory(_, mockMediator))

        environment.startAndReconnect() shouldBe Either.unit
        verify(pp.getNode.valueOrFail("node should be set"), times(2))
          .reconnectSynchronizersIgnoreFailures(any[Boolean])(
            any[TraceContext],
            any[ExecutionContext],
          )

      }

      "write ports file if desired" in new TestEnvironment {

        override def config: CantonConfig = {
          val tmp = sampleConfig.focus(_.parameters.portsFile).replace(Some("my-ports.txt"))
          (ConfigTransforms.updateAllParticipantConfigs { case (_, config) =>
            config
              .focus(_.ledgerApi)
              .replace(LedgerApiServerConfig(internalPort = Some(Port.tryCreate(42))))
          })(tmp)
        }

        val f = new java.io.File("my-ports.txt")
        f.deleteOnExit()

        val pp = mockParticipant
        when(pp.config).thenReturn(
          config.participantsByString.get("p1").valueOrFail("config should be there")
        )
        Seq("p1", "p2").foreach(setupParticipantFactory(_, pp))
        Seq("s1", "s2").foreach(setupSequencerFactory(_, mockSequencer))
        Seq("m1", "m2").foreach(setupMediatorFactory(_, mockMediator))

        clue("write ports file") {
          environment.startAndReconnect() shouldBe Either.unit
        }
        assert(f.exists())

      }

      "not start if manual start is desired" in new TestEnvironment {
        override def config: CantonConfig =
          sampleConfig.focus(_.parameters.manualStart).replace(true)

        // These would throw on start, as all methods return null.
        val mySequencer: SequencerNodeBootstrap = mock[SequencerNodeBootstrap]
        val myMediator: MediatorNodeBootstrap = mock[MediatorNodeBootstrap]
        val myParticipant: ParticipantNodeBootstrap = mock[ParticipantNodeBootstrap]

        Seq("p1", "p2").foreach(setupParticipantFactory(_, myParticipant))
        Seq("s1", "s2").foreach(setupSequencerFactory(_, mySequencer))
        Seq("m1", "m2").foreach(setupMediatorFactory(_, myMediator))

        environment.startAndReconnect() shouldBe Either.unit
      }

      "report exceptions" in new TestEnvironment {
        val exception = new RuntimeException("wurstsalat")

        Seq("p1", "p2").foreach(setupParticipantFactory(_, throw exception))
        Seq("s1", "s2").foreach(setupSequencerFactory(_, throw exception))
        Seq("m1", "m2").foreach(setupMediatorFactory(_, throw exception))

        assertThrows[RuntimeException](environment.startAndReconnect())

      }
    }
    "starting with startAll" should {
      "report exceptions" in new TestEnvironment {
        val exception = new RuntimeException("nope")

        // p1, d1 and d2 will successfully come up
        val s1: SequencerNodeBootstrap = mockSequencer
        val s2: SequencerNodeBootstrap = mockSequencer
        val m1: MediatorNodeBootstrap = mockMediator
        val m2: MediatorNodeBootstrap = mockMediator
        val p1: ParticipantNodeBootstrap = mockParticipant
        setupParticipantFactory("p1", p1)
        setupSequencerFactory("s1", s1)
        setupSequencerFactory("s2", s2)
        setupMediatorFactory("m1", m1)
        setupMediatorFactory("m2", m2)

        // p2 will fail to come up
        setupParticipantFactory("p2", throw exception)
        the[RuntimeException] thrownBy environment.startAll() shouldBe exception
        // start all will kick off stuff in the background but the "parTraverseWithLimit"
        // will terminate eagerly. so we actually have to wait until the processes finished
        // in the background
        eventually() {
          environment.sequencers.running.toSet shouldBe Set(s1, s2)
          environment.mediators.running.toSet shouldBe Set(m1, m2)
          environment.participants.running should contain.only(p1)
        }
      }
    }
  }

}
