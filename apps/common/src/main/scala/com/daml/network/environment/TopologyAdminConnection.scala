package com.daml.network.environment

import cats.syntax.traverse.*
import com.daml.nonempty.NonEmpty
import com.daml.nonempty.catsinstances.*
import com.digitalasset.canton.admin.api.client.commands.{
  TopologyAdminCommands,
  TopologyAdminCommandsX,
}
import com.digitalasset.canton.admin.api.client.data.ListPartyToParticipantResult
import com.digitalasset.canton.admin.api.client.data.topologyx.{
  ListPartyToParticipantResult as ListPartyToParticipantResultX
}
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.topology.{
  DomainId,
  MediatorId,
  Namespace,
  ParticipantId,
  PartyId,
  SequencerId,
  UniqueIdentifier,
}
import com.digitalasset.canton.topology.admin.grpc.{BaseQuery, BaseQueryX}
import com.digitalasset.canton.topology.store.{StoredTopologyTransactionsX, TimeQuery, TimeQueryX}
import StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.topology.transaction.{
  DomainParametersStateX,
  HostingParticipant,
  MediatorDomainStateX,
  ParticipantPermission,
  ParticipantPermissionX,
  PartyToParticipantX,
  RequestSide,
  SequencerDomainStateX,
  TopologyChangeOp,
  TopologyChangeOpX,
  TopologyMappingX,
  UnionspaceDefinitionX,
}
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code.{
  NamespaceDelegationX,
  OwnerToKeyMappingX,
}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

/** Connection to nodes that expose topology information (sequencer, mediator, participant)
  */
class TopologyAdminConnection(
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
  override val serviceName = "Canton Participant Admin API"

  def getId(
      useXNodes: Boolean
  )(implicit traceContext: TraceContext): Future[UniqueIdentifier] =
    if (useXNodes) {
      runCmd(
        TopologyAdminCommandsX.Init.GetId()
      )
    } else {
      runCmd(
        TopologyAdminCommands.Init.GetId()
      )
    }

  def listPartyToParticipantMappings(
      filterStore: String = "",
      operation: Option[TopologyChangeOp] = None,
      filterParty: String = "",
      filterParticipant: String = "",
      filterRequestSide: Option[RequestSide] = None,
      filterPermission: Option[ParticipantPermission] = None,
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResult]] = {
    runCmd(
      TopologyAdminCommands.Read.ListPartyToParticipant(
        BaseQuery(
          filterStore,
          useStateStore = true,
          TimeQuery.HeadState,
          operation,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParty,
        filterParticipant,
        filterRequestSide,
        filterPermission,
      )
    )
  }

  def listPartyToParticipantMappingsX(
      filterStore: String = "",
      operation: Option[TopologyChangeOpX] = None,
      filterParty: String = "",
      filterParticipant: String = "",
      timeQuery: TimeQueryX = TimeQueryX.HeadState,
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResultX]] = {
    runCmd(
      TopologyAdminCommandsX.Read.ListPartyToParticipant(
        BaseQueryX(
          filterStore,
          proposals = false,
          timeQuery,
          operation,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParty,
        filterParticipant,
      )
    )
  }

  def getSequencerState(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[SequencerDomainStateX] =
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
        .item
    }

  def getMediatorState(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[MediatorDomainStateX] =
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

  def getIdentityTransactions(
      id: UniqueIdentifier,
      domainId: Option[DomainId],
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransactionX]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = domainId.fold("")(_.filterString),
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
            id
          ) || tx.transaction.mapping.namespace == id.namespace)
        )
    }

  def getTopologySnapshot(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[GenericStoredTopologyTransactionsX] =
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

  def authorizePartyToParticipant(
      ops: TopologyChangeOp,
      party: PartyId,
      participant: ParticipantId,
      side: RequestSide,
      permission: ParticipantPermission,
  )(implicit traceContext: TraceContext): Future[Unit] =
    runCmd(
      TopologyAdminCommands.Write.AuthorizePartyToParticipant(
        ops,
        None,
        side,
        party,
        participant,
        permission,
        replaceExisting = true,
        force = false,
      )
    ).map(_ => ())

  def authorizePartyToParticipantX(
      party: PartyId,
      existingParticipants: Seq[ParticipantId],
      newParticipant: ParticipantId,
      authorizingParticipant: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Unit] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        mapping = PartyToParticipantX(
          party,
          None,
          PositiveInt.tryCreate(1), // Increase this to switch to a real consortium party
          (newParticipant +: existingParticipants).map(
            HostingParticipant(
              _,
              ParticipantPermissionX.Submission,
            )
          ),
          groupAddressing = false,
        ),
        signedBy = Seq(authorizingParticipant.uid.namespace.fingerprint),
        serial = None,
      )
    ).map(_ => ())

  def addTopologyTransactions(txs: Seq[GenericSignedTopologyTransactionX])(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      TopologyAdminCommandsX.Write.AddTransactions(txs)
    )

  def proposeSequencers(
      domainId: DomainId,
      threshold: PositiveInt,
      active: Seq[SequencerId],
      passive: Seq[SequencerId] = Seq.empty,
      signedBy: Option[Fingerprint] = None,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, SequencerDomainStateX]] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        SequencerDomainStateX
          .create(domain = domainId, threshold = threshold, active = active, observers = passive),
        signedBy.toList,
        None,
      )
    )

  def proposeMediators(
      domainId: DomainId,
      group: NonNegativeInt,
      threshold: PositiveInt,
      active: Seq[MediatorId],
      passive: Seq[MediatorId] = Seq.empty,
      signedBy: Option[Fingerprint] = None,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, MediatorDomainStateX]] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        MediatorDomainStateX
          .create(
            domain = domainId,
            group = group,
            threshold = threshold,
            active = active,
            observers = passive,
          ),
        signedBy.toList,
        None,
      )
    )

  def proposeUnionspace(
      namespace: Namespace,
      owners: NonEmpty[Set[Namespace]],
      threshold: PositiveInt,
      signedBy: Option[Fingerprint],
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, UnionspaceDefinitionX]] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        UnionspaceDefinitionX.create(
          namespace,
          threshold,
          owners,
        ),
        signedBy = signedBy.toList,
        None,
      )
    )

  def proposeDomainParameters(
      domainId: DomainId,
      parameters: DynamicDomainParameters,
      signedBy: Option[Fingerprint],
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, DomainParametersStateX]] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        DomainParametersStateX(domainId, parameters),
        signedBy = signedBy.toList,
        None,
      )
    )
}

object TopologyAdminConnection {
  def proposeCollectively[T <: TopologyMappingX](
      connections: NonEmpty[List[TopologyAdminConnection]]
  )(
      f: (
          TopologyAdminConnection,
          UniqueIdentifier,
      ) => Future[SignedTopologyTransactionX[TopologyChangeOpX, T]]
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, T]] = {
    connections.toNEF
      .traverse { con =>
        con.getId(true).flatMap(f(con, _))
      }
      .map(_.reduceLeft[SignedTopologyTransactionX[TopologyChangeOpX, T]] { case (a, b) =>
        a.addSignatures(b.signatures.toSeq)
      })
  }
}
