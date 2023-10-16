package com.daml.network.environment

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.nonempty.NonEmpty
import com.daml.nonempty.catsinstances.*
import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommandsX
import com.digitalasset.canton.admin.api.client.data.topologyx.{
  BaseResult,
  ListMediatorDomainStateResult,
  ListSequencerDomainStateResult,
  ListUnionspaceDefinitionResult,
}
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt, PositiveLong}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.pretty.PrettyUtil.*
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.time.{Clock, RemoteClock, WallClock}
import com.digitalasset.canton.topology.{
  DomainId,
  MediatorId,
  Member,
  Namespace,
  ParticipantId,
  PartyId,
  SequencerId,
  UniqueIdentifier,
}
import com.digitalasset.canton.topology.admin.grpc.BaseQueryX
import com.digitalasset.canton.topology.store.{StoredTopologyTransactionsX, TimeQueryX}
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore
import StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.daml.network.config.CNThresholds.getPartyToParticipantThreshold
import com.digitalasset.canton.topology.transaction.{
  DomainParametersStateX,
  HostingParticipant,
  MediatorDomainStateX,
  ParticipantPermissionX,
  PartyToParticipantX,
  SequencerDomainStateX,
  SignedTopologyTransactionX,
  TopologyChangeOpX,
  TopologyMappingX,
  TrafficControlStateX,
  UnionspaceDefinitionX,
  VettedPackagesX,
}
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code.{
  IdentifierDelegationX,
  NamespaceDelegationX,
  OwnerToKeyMappingX,
}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.{Duration, Instant}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

/** Connection to nodes that expose topology information (sequencer, mediator, participant)
  */
class TopologyAdminConnection(
    config: ClientConfig,
    loggerFactory: NamedLoggerFactory,
    override protected[this] val retryProvider: RetryProvider,
    clock: Clock,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(
      config,
      loggerFactory,
    )
    with RetryProvider.Has {
  import TopologyAdminConnection.TopologyResult

  override val serviceName = "Canton Participant Admin API"

  def getId(
  )(implicit traceContext: TraceContext): Future[UniqueIdentifier] = runCmd(
    TopologyAdminCommandsX.Init.GetId()
  )

  def listPartyToParticipant(
      filterStore: String = "",
      operation: Option[TopologyChangeOpX] = None,
      filterParty: String = "",
      filterParticipant: String = "",
      timeQuery: TimeQueryX = TimeQueryX.HeadState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipantX]]] = {
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
    ).map(_.map(r => TopologyResult(r.context, r.item)))
  }

  def getPartyToParticipant(
      domainId: DomainId,
      partyId: PartyId,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipantX]] =
    listPartyToParticipant(domainId.filterString, filterParty = partyId.filterString).map { txs =>
      txs.headOption.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"No PartyToParticipantX state for $partyId on domain $domainId")
          .asRuntimeException
      )
    }

  def getSequencerDomainState(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerDomainStateX]] =
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
      val ListSequencerDomainStateResult(base, mapping) = txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No sequencer state for domain $domainId")
            .asRuntimeException()
        )
      TopologyResult(base, mapping)
    }

  def getMediatorDomainState(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorDomainStateX]] =
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
      val ListMediatorDomainStateResult(base, mapping) = txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No mediator state for domain $domainId")
            .asRuntimeException()
        )
      TopologyResult(base, mapping)
    }

  def getUnionspaceDefinition(domainId: DomainId, unionspace: Namespace)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[UnionspaceDefinitionX]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListUnionspaceDefinition(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterNamespace = unionspace.toProtoPrimitive,
      )
    ).map { txs =>
      val ListUnionspaceDefinitionResult(base, mapping) = txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(show"No unionspace definition for $unionspace on domain $domainId")
            .asRuntimeException()
        )
      TopologyResult(base, mapping)
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

  def getIdentityBootstrapTransactions(id: UniqueIdentifier)(implicit
      traceContext: TraceContext
  ): Future[Seq[GenericSignedTopologyTransactionX]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = "Authorized",
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = id.namespace.fingerprint.toProtoPrimitive,
          protocolVersion = None,
        )
      )
    ).map { transactions =>
      transactions.result
        .map(_.transaction)
        .filter(tx =>
          Set(NamespaceDelegationX, OwnerToKeyMappingX, IdentifierDelegationX)
            .contains(tx.transaction.mapping.code)
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

  def lookupTrafficControlState(
      domainId: DomainId,
      member: Member,
  )(implicit traceContext: TraceContext): Future[Option[TopologyResult[TrafficControlStateX]]] = {
    runCmd(
      TopologyAdminCommandsX.Read.ListTrafficControlState(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterMember = member.filterString,
      )
    ).map(_.headOption.map(tx => TopologyResult(tx.context, tx.item)))
  }

  def getTrafficControlState(domainId: DomainId, member: Member)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[TrafficControlStateX]] = {
    lookupTrafficControlState(domainId, member).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"No traffic control state for $member on domain $domainId")
          .asRuntimeException
      )
    )
  }

  def proposeMapping[M <: TopologyMappingX: ClassTag](
      mapping: M,
      signedBy: Fingerprint,
      serial: PositiveInt,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransactionX[TopologyChangeOpX, M]] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        mapping = mapping,
        signedBy = Seq(signedBy),
        store = AuthorizedStore.filterName,
        serial = Some(serial),
      )
    )

  def proposeMapping[M <: TopologyMappingX: ClassTag](
      mapping: Either[String, M],
      signedBy: Fingerprint,
      serial: PositiveInt,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransactionX[TopologyChangeOpX, M]] =
    proposeMapping(
      mapping.valueOr(err => throw new IllegalArgumentException(s"Invalid topology mapping: $err")),
      signedBy,
      serial,
    )

  def ensureTopologyMapping[M <: TopologyMappingX: ClassTag](
      description: String,
      check: => Future[Either[TopologyResult[M], Unit]],
      update: M => Either[String, M],
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.ensureThat[TopologyResult[M], Unit](
      description,
      check = check,
      establish = { case TopologyResult(baseResult, mapping) =>
        val updatedMapping = update(mapping)
        proposeMapping(
          updatedMapping,
          signedBy,
          serial = baseResult.serial + PositiveInt.one,
        ).map(_ => ())
      },
      logger,
    )

  protected def proposeInitialPartyToParticipant(
      partyId: PartyId,
      participantId: ParticipantId,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[Unit] =
    proposeMapping(
      PartyToParticipantX(
        partyId,
        None,
        PositiveInt.one,
        Seq(
          HostingParticipant(
            participantId,
            ParticipantPermissionX.Submission,
          )
        ),
        groupAddressing = false,
      ),
      signedBy = signedBy,
      serial = PositiveInt.one,
    ).map(_ => ())

  def ensurePartyToParticipant(
      domainId: DomainId,
      party: PartyId,
      newParticipant: ParticipantId,
      signedBy: Fingerprint,
      svcRulesMembersSize: Int,
  )(implicit traceContext: TraceContext): Future[Unit] =
    ensureTopologyMapping[PartyToParticipantX](
      show"Party $party is authorized on $newParticipant",
      getPartyToParticipant(domainId, party).map(result =>
        Either.cond(
          result.mapping.participants
            .exists(hosting => hosting.participantId == newParticipant),
          (),
          result,
        )
      ),
      previous =>
        Right(
          previous.copy(
            participants = HostingParticipant(
              newParticipant,
              ParticipantPermissionX.Submission,
            ) +: previous.participants,
            threshold = getPartyToParticipantThreshold(
              svcRulesMembersSize,
              previous.participants.length,
            ),
          )
        ),
      signedBy,
    )

  def addTopologyTransactions(txs: Seq[GenericSignedTopologyTransactionX])(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      TopologyAdminCommandsX.Write.AddTransactions(txs, AuthorizedStore.filterName)
    )

  def proposeInitialSequencerDomainState(
      domainId: DomainId,
      active: Seq[SequencerId],
      observers: Seq[SequencerId],
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, SequencerDomainStateX]] =
    proposeMapping(
      SequencerDomainStateX.create(
        domainId,
        PositiveInt.one,
        active,
        observers,
      ),
      signedBy,
      serial = PositiveInt.one,
    )

  def ensureSequencerDomainState(
      domainId: DomainId,
      newActiveSequencer: SequencerId,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    ensureTopologyMapping[SequencerDomainStateX](
      show"Sequencer $newActiveSequencer is active in SequencerDomainState",
      getSequencerDomainState(domainId).map(result =>
        Either.cond(result.mapping.active.contains(newActiveSequencer), (), result)
      ),
      previous =>
        // constructor is not exposed so no copy
        SequencerDomainStateX.create(
          previous.domain,
          previous.threshold,
          newActiveSequencer +: previous.active,
          previous.observers,
        ),
      signedBy,
    )

  def proposeInitialMediatorDomainState(
      domainId: DomainId,
      group: NonNegativeInt,
      active: Seq[MediatorId],
      observers: Seq[MediatorId],
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, MediatorDomainStateX]] =
    proposeMapping(
      MediatorDomainStateX.create(
        domain = domainId,
        group = group,
        threshold = PositiveInt.one,
        active = active,
        observers = observers,
      ),
      signedBy,
      serial = PositiveInt.one,
    )

  // TODO(#7884): handle threshold update for sv off-boarding; remove temporary workaround to use svcRulesMemberSize
  def ensureMediatorDomainState(
      domainId: DomainId,
      newActiveMediator: MediatorId,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    ensureTopologyMapping[MediatorDomainStateX](
      show"Mediator $newActiveMediator is active in MediatorDomainState",
      getMediatorDomainState(domainId).map(result =>
        Either.cond(result.mapping.active.contains(newActiveMediator), (), result)
      ),
      previous =>
        // constructor is not exposed so no copy
        MediatorDomainStateX.create(
          previous.domain,
          previous.group,
          previous.threshold,
          newActiveMediator +: previous.active,
          previous.observers,
        ),
      signedBy,
    )
  }

  def proposeInitialUnionspaceDefinition(
      namespace: Namespace,
      owners: NonEmpty[Set[Namespace]],
      threshold: PositiveInt,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, UnionspaceDefinitionX]] =
    proposeMapping(
      UnionspaceDefinitionX.create(
        namespace,
        threshold,
        owners,
      ),
      signedBy = signedBy,
      serial = PositiveInt.one,
    )

  def ensureUnionspaceDefinition(
      domainId: DomainId,
      unionspace: Namespace,
      newOwner: Namespace,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    ensureTopologyMapping[UnionspaceDefinitionX](
      show"Namespace $newOwner is in owners of UnionspaceDefinition",
      getUnionspaceDefinition(domainId, unionspace).map(result =>
        Either.cond(result.mapping.owners.contains(newOwner), (), result)
      ),
      previous =>
        // constructor is not exposed so no copy
        UnionspaceDefinitionX.create(
          previous.unionspace,
          previous.threshold,
          previous.owners.incl(newOwner),
        ),
      signedBy,
    )

  def ensureTrafficControlState(
      domainId: DomainId,
      member: Member,
      newTotalExtraTrafficLimit: Long,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.ensureThat[Option[TopologyResult[TrafficControlStateX]], Unit](
      s"Extra traffic limit for $member on domain $domainId set to $newTotalExtraTrafficLimit (or higher)",
      lookupTrafficControlState(domainId, member).map(result =>
        Either.cond(
          result.fold(0L)(_.mapping.totalExtraTrafficLimit.value) >= newTotalExtraTrafficLimit,
          (),
          result,
        )
      ),
      previousOrNone =>
        proposeMapping(
          TrafficControlStateX
            .create(
              domainId,
              member,
              PositiveLong.tryCreate(newTotalExtraTrafficLimit),
            ),
          signedBy,
          serial = previousOrNone.fold(PositiveInt.one)(_.base.serial + PositiveInt.one),
        ).map(_ => ()),
      logger,
    )

  def proposeInitialDomainParameters(
      domainId: DomainId,
      parameters: DynamicDomainParameters,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, DomainParametersStateX]] =
    proposeMapping(
      DomainParametersStateX(domainId, parameters),
      signedBy = signedBy,
      serial = PositiveInt.one,
    )

  def waitForTopologyChangeToBeValid(description: String, validFrom: Instant)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    val validFromSkewed = CantonTimestamp
      .assertFromInstant(validFrom)
      .plus(TopologyAdminConnection.TOPOLOGY_CHANGE_SKEW)
    logger.info(
      s"Waiting for topology change $description to be valid at $validFrom (with skew $validFromSkewed)"
    )
    clock match {
      case _: WallClock =>
        clock
          .scheduleAt(_ => (), validFromSkewed)
          .failOnShutdownTo(
            new RuntimeException(s"Aborting waiting for topology change due to node shutting down")
          )
          .map { _ =>
            logger.debug("Topology change is now valid")
          }
      case _: RemoteClock =>
        logger.debug("Running in simtime mode, topology change is valid immediately")
        Future.unit
      case c => sys.error(s"Unknown clock config: $c")
    }
  }

  def listVettedPackages(
      participantId: ParticipantId,
      domainId: DomainId,
      timeQuery: TimeQueryX = TimeQueryX.HeadState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[VettedPackagesX]]] = {
    runCmd(
      TopologyAdminCommandsX.Read.ListVettedPackages(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = false,
          timeQuery,
          None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        participantId.filterString,
      )
    ).map(_.map(r => TopologyResult(r.context, r.item)))
  }

  def initId(participantId: ParticipantId)(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(TopologyAdminCommandsX.Init.InitId(participantId.uid.toProtoPrimitive))
  }
}

object TopologyAdminConnection {
  val TOPOLOGY_CHANGE_SKEW: Duration = Duration.ofMillis(100)

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
        con.getId().flatMap(f(con, _))
      }
      .map(_.reduceLeft[SignedTopologyTransactionX[TopologyChangeOpX, T]] { case (a, b) =>
        a.addSignatures(b.signatures.toSeq)
      })
  }

  final case class TopologyResult[M <: TopologyMappingX](
      base: BaseResult,
      mapping: M,
  ) extends PrettyPrinting {
    override val pretty = prettyNode(
      "TopologyResult",
      param("base", _.base),
      param("mapping", _.mapping),
    )
  }

  implicit val prettyBaseResult: Pretty[BaseResult] =
    prettyNode(
      "BaseResult",
      param("domain", _.domain.unquoted),
      param("validFrom", _.validFrom),
      param("validUntil", _.validUntil),
      param("operation", _.operation),
      param("transactionHash", _.transactionHash),
      param("serial", _.serial),
      param("signedBy", _.signedBy),
    )
}
