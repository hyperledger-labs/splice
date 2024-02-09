package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.commands.{
  EnterpriseSequencerAdminCommands,
  SequencerAdminCommands,
  StatusAdminCommands,
}
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.sequencing.admin.grpc.InitializeSequencerResponseX
import com.digitalasset.canton.domain.sequencing.sequencer.{
  SequencerPruningStatus,
  SequencerSnapshot,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.health.admin.data.{NodeStatus, SequencerNodeStatus}
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{Member, NodeIdentity, SequencerId}
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.domain.sequencing.sequencer.traffic.SequencerTrafficStatus
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton sequencer admin API that we rely
  * on in our own applications.
  */
class SequencerAdminConnection(
    config: ClientConfig,
    loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    clock: Clock,
)(implicit ec: ExecutionContextExecutor)
    extends TopologyAdminConnection(
      config,
      loggerFactory,
      retryProvider,
      clock,
    ) {

  override val serviceName = "Canton Sequencer Admin API"

  private val sequencerStatusCommand =
    new StatusAdminCommands.GetStatus(SequencerNodeStatus.fromProtoV30)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[SequencerNodeStatus]] =
    runCmd(
      sequencerStatusCommand
    )

  def getSequencerId(implicit traceContext: TraceContext): Future[SequencerId] =
    getId().map(SequencerId(_))

  def getSequencerSnapshot(instant: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[SequencerSnapshot] =
    runCmd(
      EnterpriseSequencerAdminCommands.Snapshot(instant)
    )

  def initialize(
      topologySnapshot: GenericStoredTopologyTransactionsX,
      domainParameters: StaticDomainParameters,
      sequencerSnapshot: Option[SequencerSnapshot],
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponseX] =
    runCmd(
      EnterpriseSequencerAdminCommands.InitializeX(
        topologySnapshot,
        domainParameters,
        sequencerSnapshot,
      )
    )

  def getSequencerTrafficStatus(filterMembers: Seq[Member] = Seq.empty)(implicit
      traceContext: TraceContext
  ): Future[SequencerTrafficStatus] =
    runCmd(
      SequencerAdminCommands.GetTrafficControlState(filterMembers)
    )

  def getSequencerPruningStatus()(implicit
      traceContext: TraceContext
  ): Future[SequencerPruningStatus] =
    runCmd(
      SequencerAdminCommands.GetPruningStatus
    )

  def prune(ts: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[String] =
    runCmd(
      EnterpriseSequencerAdminCommands.Prune(ts)
    )

  def disableMember(member: Member)(implicit
      traceContext: TraceContext
  ): Future[Unit] = runCmd(
    EnterpriseSequencerAdminCommands.DisableMember(member)
  )

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] =
    getSequencerId

  override def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] = {
    getStatus.map {
      case NodeStatus.Failure(_) => false
      case NodeStatus.NotInitialized(_) => false
      case NodeStatus.Success(_) => true
    }
  }
}
