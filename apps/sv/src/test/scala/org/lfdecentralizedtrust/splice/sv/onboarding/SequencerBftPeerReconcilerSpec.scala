package org.lfdecentralizedtrust.splice.sv.onboarding

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.RequireTypes
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.bindings.p2p.grpc.P2PGrpcNetworking.P2PEndpoint
import com.digitalasset.canton.topology.{
  PhysicalSynchronizerId,
  SequencerId,
  SynchronizerId,
  UniqueIdentifier,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.automation.{TaskNoop, TaskOutcome}
import org.lfdecentralizedtrust.splice.codegen.java.splice.cometbft.{
  CometBftConfig,
  CometBftNodeConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  SequencerConfig,
  SynchronizerNodeConfig,
}
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.SingleScanConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BftSequencer
import org.lfdecentralizedtrust.splice.store.DsoRulesStore.DsoRulesWithSvNodeStates
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.test.HasRetryProvider
import org.scalatest.flatspec.AnyFlatSpec

import scala.compat.java8.OptionConverters.RichOptionForJava8
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters.{MapHasAsJava, SeqHasAsJava}

class SequencerBftPeerReconcilerSpec extends AnyFlatSpec with BaseTest with HasRetryProvider {

  private val serialId = 123L
  private val selfSequencerId = SequencerId(
    UniqueIdentifier.tryFromProtoPrimitive("sequencer::self")
  )
  private val sequencer1Id = SequencerId(UniqueIdentifier.tryFromProtoPrimitive("seq::1"))
  private val sequencer1Host = createP2PEndpoint("host")
  private val sequencer2Id = SequencerId(UniqueIdentifier.tryFromProtoPrimitive("seq::2"))
  private val sequencer2Host = createP2PEndpoint("host2")

  private val svDsoStoreMock = mock[SvDsoStore]
  private val sequencerAdminConnection = mock[SequencerAdminConnection]
  private val scanConnection = mock[AggregatingScanConnection]

  when(sequencerAdminConnection.getSequencerId).thenReturn(Future.successful(selfSequencerId))
  when(sequencerAdminConnection.isNodeInitialized()(any[TraceContext]))
    .thenReturn(Future.successful(true))
  when(sequencerAdminConnection.getPhysicalSynchronizerId()(any[TraceContext])).thenReturn(
    Future.successful(
      PhysicalSynchronizerId(
        SynchronizerId(UniqueIdentifier.tryFromProtoPrimitive("synchronizer::test")),
        NonNegativeInt.tryCreate(serialId.toInt),
        ProtocolVersion.latest,
      )
    )
  )

  private val reconciler = new SequencerBftPeerReconciler(
    sequencerAdminConnection,
    scanConnection,
    testRetryProvider,
  ) {
    override protected def svDsoStore: SvDsoStore = svDsoStoreMock

    override def reconcileTask(
        task: SequencerBftPeerReconciler.BftPeerDifference
    )(implicit tc: TraceContext, ec: ExecutionContext): Future[TaskOutcome] =
      Future.successful(TaskNoop)

    override protected def loggerFactory: NamedLoggerFactory =
      SequencerBftPeerReconcilerSpec.this.loggerFactory
  }

  "SequencerBftPeerReconciler" should "add all sequencers when none are present in current peers, ignoring self" in {
    withConfiguredDsoSequencers(
      Seq(
        createSequencerConfig(sequencer1Id),
        createSequencerConfig(sequencer2Id),
        createSequencerConfig(selfSequencerId),
      )
    )

    withScanSequencers(
      BftSequencer(
        serialId,
        sequencer1Id,
        sequencer1Host.url,
      ),
      BftSequencer(
        serialId,
        sequencer2Id,
        sequencer2Host.url,
      ),
      BftSequencer(
        serialId,
        selfSequencerId,
        "https://host3:7214",
      ),
    )

    when(sequencerAdminConnection.listCurrentOutgoingPeerEndpoints())
      .thenReturn(Future.successful(Seq.empty))

    val result = reconciler.diffDsoRulesWithTopology().futureValue.loneElement
    result.toAdd should have size 2
    result.toRemove should be(empty)
  }

  it should "remove sequencer that was removed from the dso state" in {
    withConfiguredDsoSequencers(
      Seq(
        createSequencerConfig(sequencer1Id)
      )
    )

    withScanSequencers(
      BftSequencer(
        serialId,
        sequencer1Id,
        sequencer1Host.url,
      ),
      BftSequencer(
        serialId,
        sequencer2Id,
        sequencer2Host.url,
      ),
    )

    when(sequencerAdminConnection.listCurrentOutgoingPeerEndpoints())
      .thenReturn(
        Future.successful(
          Seq(
            (Some(sequencer1Id), sequencer1Host),
            (Some(sequencer2Id), sequencer2Host),
          )
        )
      )

    val result = reconciler.diffDsoRulesWithTopology().futureValue.loneElement
    result.toAdd should be(empty)
    result.toRemove should contain only sequencer2Host
  }

  it should "do nothing when the dso state is the same as the current peers" in {
    withConfiguredDsoSequencers(
      Seq(
        createSequencerConfig(sequencer1Id),
        createSequencerConfig(sequencer2Id),
      )
    )

    withScanSequencers(
      BftSequencer(
        serialId,
        sequencer1Id,
        sequencer1Host.url,
      ),
      BftSequencer(
        serialId,
        sequencer2Id,
        sequencer2Host.url,
      ),
    )

    when(sequencerAdminConnection.listCurrentOutgoingPeerEndpoints())
      .thenReturn(
        Future.successful(
          Seq(
            (Some(sequencer1Id), sequencer1Host),
            (Some(sequencer2Id), sequencer2Host),
          )
        )
      )

    val result = reconciler.diffDsoRulesWithTopology().futureValue
    result should be(empty)
  }

  it should "do nothing when scan doesn't contain the sequencer info but the dso state contains it" in {
    withConfiguredDsoSequencers(
      Seq(
        createSequencerConfig(sequencer1Id),
        createSequencerConfig(sequencer2Id),
      )
    )

    withScanSequencers(
      BftSequencer(
        serialId,
        sequencer1Id,
        sequencer1Host.url,
      )
    )

    when(sequencerAdminConnection.listCurrentOutgoingPeerEndpoints())
      .thenReturn(
        Future.successful(
          Seq(
            (Some(sequencer1Id), sequencer1Host),
            (Some(sequencer2Id), sequencer2Host),
          )
        )
      )

    val result = reconciler.diffDsoRulesWithTopology().futureValue
    result should be(empty)
  }

  it should "update a sequencer connection by adding it and removing it when the scan p2p url changes" in {
    withConfiguredDsoSequencers(
      Seq(
        createSequencerConfig(sequencer1Id)
      )
    )

    val newSequencer1Host = createP2PEndpoint("newhost")

    withScanSequencers(
      BftSequencer(
        serialId,
        sequencer1Id,
        newSequencer1Host.url,
      )
    )

    when(sequencerAdminConnection.listCurrentOutgoingPeerEndpoints())
      .thenReturn(
        Future.successful(
          Seq(
            (Some(sequencer1Id), sequencer1Host)
          )
        )
      )

    val result = reconciler.diffDsoRulesWithTopology().futureValue.loneElement
    result.toAdd.map(_.id) should contain only newSequencer1Host
    result.toRemove should contain only sequencer1Host
  }

  it should "ignore sequencer urls from scan for other serial ids" in {
    withConfiguredDsoSequencers(
      Seq(
        createSequencerConfig(sequencer1Id)
      )
    )

    withScanSequencers(
      BftSequencer(
        serialId + 1,
        sequencer2Id,
        sequencer2Host.url,
      ),
      BftSequencer(
        serialId + 1,
        sequencer1Id,
        sequencer1Host.url,
      ),
    )

    when(sequencerAdminConnection.listCurrentOutgoingPeerEndpoints())
      .thenReturn(
        Future.successful(
          Seq(
            (Some(sequencer1Id), sequencer1Host)
          )
        )
      )

    val result = reconciler.diffDsoRulesWithTopology().futureValue
    result should be(empty)
  }

  it should "keep sequencer that does not have a sequencer id in the peer info yet and is returned by scan" in {
    withConfiguredDsoSequencers(
      Seq(
        createSequencerConfig(sequencer1Id)
      )
    )

    withScanSequencers(
      BftSequencer(
        serialId,
        sequencer1Id,
        sequencer1Host.url,
      )
    )

    when(sequencerAdminConnection.listCurrentOutgoingPeerEndpoints())
      .thenReturn(
        Future.successful(
          Seq(
            (None, sequencer1Host)
          )
        )
      )

    reconciler.diffDsoRulesWithTopology().futureValue should be(empty)
  }

  it should "remove sequencer that does not have a sequencer id in the peer info yet and is not returned by scan" in {
    withConfiguredDsoSequencers(
      Seq(
        createSequencerConfig(sequencer1Id)
      )
    )

    withScanSequencers()

    when(sequencerAdminConnection.listCurrentOutgoingPeerEndpoints())
      .thenReturn(
        Future.successful(
          Seq(
            (None, sequencer1Host)
          )
        )
      )

    val result = reconciler.diffDsoRulesWithTopology().futureValue.loneElement
    result.toAdd should be(empty)
    result.toRemove should contain only sequencer1Host
  }

  it should "do nothing if the sequencer is not initialized" in {
    withConfiguredDsoSequencers(
      Seq(
        createSequencerConfig(sequencer1Id),
        createSequencerConfig(sequencer2Id),
      )
    )

    when(sequencerAdminConnection.isNodeInitialized()(any[TraceContext]))
      .thenReturn(Future.successful(false))

    try {
      val result = reconciler.diffDsoRulesWithTopology().futureValue
      result should be(empty)
    } finally {
      when(sequencerAdminConnection.isNodeInitialized()(any[TraceContext]))
        .thenReturn(Future.successful(true))
    }
  }

  private def createSequencerConfig(id: SequencerId) = {
    new SynchronizerNodeConfig(
      new CometBftConfig(
        Map.empty[String, CometBftNodeConfig].asJava,
        List.empty.asJava,
        List.empty.asJava,
      ),
      Some(
        new SequencerConfig(
          0,
          id.toProtoPrimitive,
          "",
          None.asJava,
        )
      ).asJava,
      None.asJava,
      None.asJava,
      None.asJava,
      None.asJava,
      java.util.Optional.empty(),
    )
  }

  private def withConfiguredDsoSequencers(dsoSequencerConfigs: Seq[SynchronizerNodeConfig]) = {
    val states = mock[DsoRulesWithSvNodeStates]
    when(svDsoStoreMock.getDsoRulesWithSvNodeStates())
      .thenReturn(
        Future.successful(
          states
        )
      )
    when(states.currentSynchronizerNodeConfigs()).thenReturn(
      dsoSequencerConfigs
    )
  }

  private def withScanSequencers(scanSequencers: BftSequencer*) = {
    when(
      scanConnection.fromAllScans[Seq[BftSequencer]](eqTo(false))(
        any[SingleScanConnection => Future[Seq[BftSequencer]]]
      )(any[TraceContext])
    )
      .thenReturn(
        Future.successful(
          Seq(
            scanSequencers
          )
        )
      )
  }

  private def createP2PEndpoint(id: String): P2PEndpoint.Id = {
    P2PEndpoint.Id(
      id,
      RequireTypes.Port.tryCreate(777),
      transportSecurity = true,
    )
  }
}
