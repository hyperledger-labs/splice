package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.commands.{
  EnterpriseSequencerAdminCommands,
  StatusAdminCommands,
  TopologyAdminCommandsX,
}
import com.digitalasset.canton.admin.api.client.data.topologyx.ListPartyToParticipantResult
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.sequencing.admin.grpc.InitializeSequencerResponseX
import com.digitalasset.canton.domain.sequencing.sequencer.SequencerSnapshot
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.health.admin.data.{NodeStatus, SequencerNodeStatus}
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.topology.{DomainId, PartyId, SequencerId}
import com.digitalasset.canton.topology.admin.grpc.BaseQueryX
import com.digitalasset.canton.topology.store.{StoredTopologyTransactionsX, TimeQueryX}
import StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.topology.transaction.{TopologyChangeOpX, TopologyMappingX}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import TopologyMappingX.Code.{NamespaceDelegationX, OwnerToKeyMappingX}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton sequencer admin API that we rely
  * on in our own applications.
  */
class SequencerAdminConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(
      config,
      timeouts,
      loggerFactory,
      // The version endpoint is only injected into our own apps so we cannot run this against the admin API.
      enableVersionCompatCheck = false,
    ) {

  override val serviceName = "Canton Sequencer Admin API"

  private val sequencerStatusCommand =
    new StatusAdminCommands.GetStatus(SequencerNodeStatus.fromProtoV0)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[SequencerNodeStatus]] =
    runCmd(
      sequencerStatusCommand
    )

  def getSequencerId(implicit traceContext: TraceContext): Future[SequencerId] =
    runCmd(
      TopologyAdminCommandsX.Init.GetId()
    ).map(SequencerId(_))

  def getSequencerIdentityTransactions(
      id: SequencerId
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransactionX]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = "",
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        )
      )
    ).map { transactions =>
      transactions.result
        .map(_.transaction)
        .filter(tx =>
          Set(NamespaceDelegationX, OwnerToKeyMappingX).contains(
            tx.transaction.mapping.code
          ) && (tx.transaction.mapping.maybeUid.contains(
            id.uid
          ) || tx.transaction.mapping.namespace == id.uid.namespace)
        )
    }

  def getSequencerState(domainId: DomainId)(implicit traceContext: TraceContext) =
    runCmd(
      TopologyAdminCommandsX.Read.SequencerDomainState(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterDomain = "",
      )
    ).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No sequencer state for domain $domainId")
            .asRuntimeException()
        )
    }

  def getMediatorState(domainId: DomainId)(implicit traceContext: TraceContext) =
    runCmd(
      TopologyAdminCommandsX.Read.MediatorDomainState(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterDomain = "",
      )
    ).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No mediator state for domain $domainId")
            .asRuntimeException()
        )
        .item
    }

  def getPartyToParticipantState(
      partyId: PartyId
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResult]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListPartyToParticipant(
        BaseQueryX(
          filterStore = "",
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParty = partyId.toProtoPrimitive,
        filterParticipant = "",
      )
    )

  def getTopologySnapshot(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[StoredTopologyTransactionsX[TopologyChangeOpX, TopologyMappingX]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        )
      )
    )

  def getSequencerSnapshot(ts: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[SequencerSnapshot] =
    runCmd(
      EnterpriseSequencerAdminCommands.Snapshot(ts)
    )

  def initialize(
      topologySnapshot: GenericStoredTopologyTransactionsX,
      domainParameters: StaticDomainParameters,
      sequencerSnapshot: SequencerSnapshot,
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponseX] =
    runCmd(
      EnterpriseSequencerAdminCommands.InitializeX(
        topologySnapshot,
        domainParameters,
        Some(sequencerSnapshot),
      )
    )
}
