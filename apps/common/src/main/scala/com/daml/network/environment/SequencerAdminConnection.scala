package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.commands.{
  EnterpriseSequencerAdminCommands,
  SequencerAdminCommands,
  StatusAdminCommands,
}
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.sequencing.admin.grpc.InitializeSequencerResponseX
import com.digitalasset.canton.domain.sequencing.sequencer.SequencerSnapshot
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
    new StatusAdminCommands.GetStatus(SequencerNodeStatus.fromProtoV0)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[SequencerNodeStatus]] =
    runCmd(
      sequencerStatusCommand
    )

  def getSequencerId(implicit traceContext: TraceContext): Future[SequencerId] =
    getId().map(SequencerId(_))

  def getSequencerSnapshot(ts: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[SequencerSnapshot] =
    runCmd(
      EnterpriseSequencerAdminCommands.Snapshot(ts)
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

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] =
    getSequencerId
}
