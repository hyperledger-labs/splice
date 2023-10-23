package com.daml.network.environment

import cats.data.OptionT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.config.CNThresholds
import com.daml.network.environment.RetryProvider.{CheckReset, ConditionNotSatisfied}
import com.daml.nonempty.NonEmpty
import com.daml.nonempty.catsinstances.*
import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommandsX
import com.digitalasset.canton.admin.api.client.data.topologyx.{
  BaseResult,
  ListMediatorDomainStateResult,
  ListSequencerDomainStateResult,
}
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt, PositiveLong}
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.PrettyUtil.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.time.{Clock, RemoteClock, WallClock}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.admin.grpc.BaseQueryX
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.{TimeQueryX, TopologyStoreId}
import com.digitalasset.canton.topology.transaction.*
import com.daml.network.config.CNThresholds.getPartyToParticipantThreshold
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code.{
  IdentifierDelegationX,
  NamespaceDelegationX,
  OwnerToKeyMappingX,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}

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
      proposals: Boolean = false,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipantX]]] = {
    runCmd(
      TopologyAdminCommandsX.Read.ListPartyToParticipant(
        BaseQueryX(
          filterStore,
          proposals = proposals,
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

  private def findPartyToParticipant(
      domainId: DomainId,
      partyId: PartyId,
  )(implicit traceContext: TraceContext): OptionT[Future, TopologyResult[PartyToParticipantX]] =
    OptionT(
      listPartyToParticipant(
        filterStore = domainId.filterString,
        filterParty = partyId.filterString,
      ).map { txs =>
        txs.headOption
      }
    )

  def getPartyToParticipant(
      domainId: DomainId,
      partyId: PartyId,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipantX]] =
    findPartyToParticipant(domainId, partyId).getOrElse {
      throw Status.NOT_FOUND
        .withDescription(s"No PartyToParticipantX state for $partyId on domain $domainId")
        .asRuntimeException
    }

  def getSequencerDomainState(domainId: DomainId, proposals: Boolean = false)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerDomainStateX]] =
    runCmd(
      TopologyAdminCommandsX.Read.SequencerDomainState(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = proposals,
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

  def getMediatorDomainState(domainId: DomainId, proposals: Boolean = false)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorDomainStateX]] =
    runCmd(
      TopologyAdminCommandsX.Read.MediatorDomainState(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = proposals,
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

  def getUnionspaceDefinition(
      domainId: DomainId,
      unionspace: Namespace,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[UnionspaceDefinitionX]] =
    listUnionspaceDefinition(domainId, unionspace).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(show"No unionspace definition for $unionspace on domain $domainId")
            .asRuntimeException()
        )
    }

  private def listUnionspaceDefinition(
      domainId: DomainId,
      unionspace: Namespace,
      proposals: Boolean = false,
  )(implicit tc: TraceContext) = {
    runCmd(
      TopologyAdminCommandsX.Read.ListUnionspaceDefinition(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = proposals,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterNamespace = unionspace.toProtoPrimitive,
      )
    ).map {
      _.map(result => TopologyResult(result.context, result.item))
    }
  }

  def getIdentityTransactions(
      id: UniqueIdentifier,
      domainId: Option[DomainId],
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransactionX]] =
    getTransactions(
      Set(NamespaceDelegationX, OwnerToKeyMappingX),
      Some(id),
      domainId,
    )

  private def getTransactions(
      transactionType: Set[TopologyMappingX.Code],
      id: Option[UniqueIdentifier],
      domainId: Option[DomainId],
      proposals: Boolean = false,
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransactionX]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = domainId.fold("")(_.filterString),
          proposals = proposals,
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
          transactionType.contains(
            tx.transaction.mapping.code
          ) && id.forall(id =>
            tx.transaction.mapping.maybeUid.contains(
              id
            ) || tx.transaction.mapping.namespace == id.namespace
          )
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

  private def proposeMapping[M <: TopologyMappingX: ClassTag](
      store: TopologyStoreId,
      mapping: M,
      signedBy: Fingerprint,
      serial: PositiveInt,
      isProposal: Boolean,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransactionX[TopologyChangeOpX, M]] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        mapping = mapping,
        signedBy = Seq(signedBy),
        store = store.filterName,
        serial = Some(serial),
        mustFullyAuthorize = !isProposal,
      )
    )

  private def proposeMapping[M <: TopologyMappingX: ClassTag](
      store: TopologyStoreId,
      mapping: Either[String, M],
      signedBy: Fingerprint,
      serial: PositiveInt,
      isProposal: Boolean,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransactionX[TopologyChangeOpX, M]] =
    proposeMapping(
      store,
      mapping.valueOr(err => throw new IllegalArgumentException(s"Invalid topology mapping: $err")),
      signedBy,
      serial,
      isProposal,
    )

  /** Ensure that either the accepted state passes the check, a proposal exists that passes the check or a proposal is created that passes the check
    *  - check current accepted state if the check holds
    *  - if not check currently existing proposals if the check holds
    *  - if not then create proposal
    *  - run check until it holds or until the current state returned by the check has a serial that is newer than the state when we last created the proposal
    *  - if a newer state is returned by the check then re-create the proposal with a new serial and go running the check
    */
  private def ensureTopologyProposal[M <: TopologyMappingX: ClassTag](
      store: TopologyStoreId,
      description: String,
      check: Boolean => Future[Either[TopologyResult[M], TopologyResult[M]]],
      update: M => Either[String, M],
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[TopologyResult[M]] =
    retryProvider.ensureThatWithReset[TopologyResult[M], TopologyResult[M]](
      description,
      // Check accepted state
      // If not valid yet, check proposals
      // If no valid proposal then return accepted state
      check(false).flatMap {
        case Left(acceptedResult) =>
          check(true)
            .map {
              case Left(_) => Left(acceptedResult)
              case success @ Right(_) => success
            }
            .recover {
              case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
                Left(acceptedResult)
            }
        case success @ Right(_) => Future.successful(success)
      },
      establishedState =>
        check(false).flatMap {
          case Left(currentState)
              if currentState.base.serial == establishedState.lastEstablishState.base.serial =>
            check(true).map(_.leftMap(ConditionNotSatisfied(_)))
          case Left(newerAcceptedState) =>
            Future.successful(Left(CheckReset(newerAcceptedState)))
          case Right(success) => Future.successful(Right(success))
        },
      establish = { case TopologyResult(baseResult, mapping) =>
        val updatedMapping = update(mapping)
        proposeMapping(
          store,
          updatedMapping,
          signedBy,
          serial = baseResult.serial + PositiveInt.one,
          isProposal = true,
        ).map(_ => ())
      },
      logger,
    )

  private def ensureTopologyMapping[M <: TopologyMappingX: ClassTag](
      store: TopologyStoreId,
      description: String,
      check: => Future[Either[TopologyResult[M], TopologyResult[M]]],
      update: M => Either[String, M],
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[TopologyResult[M]] =
    ensureTopologyMappingA(
      store,
      description,
      check,
      check,
      update,
      signedBy,
    )

  private def ensureTopologyMappingA[M <: TopologyMappingX: ClassTag](
      store: TopologyStoreId,
      description: String,
      initialCheck: => Future[Either[TopologyResult[M], TopologyResult[M]]],
      establishedCheck: => Future[Either[TopologyResult[M], TopologyResult[M]]],
      update: M => Either[String, M],
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[TopologyResult[M]] =
    retryProvider.ensureThatI[TopologyResult[M], TopologyResult[M]](
      description,
      initialCheck,
      establishedCheck,
      establish = { case TopologyResult(baseResult, mapping) =>
        val updatedMapping = update(mapping)
        proposeMapping(
          store,
          updatedMapping,
          signedBy,
          serial = baseResult.serial + PositiveInt.one,
          isProposal = false,
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
      TopologyStoreId.AuthorizedStore,
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
      isProposal = false,
    ).map(_ => ())

  // TODO(#7884): handle threshold update for sv off-boarding
  def ensurePartyToParticipantProposal(
      domainId: DomainId,
      party: PartyId,
      newParticipant: ParticipantId,
      svcRulesMembersSize: Int,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipantX]] = {
    def findPartyToParticipant(proposal: Boolean) = {
      if (proposal) {
        listPartyToParticipant(
          filterStore = domainId.filterString,
          filterParty = party.filterString,
          proposals = proposal,
        ).map { proposals =>
          proposals
            .find(proposal =>
              proposal.mapping.participantIds.contains(
                newParticipant
              ) && proposal.mapping.threshold == getPartyToParticipantThreshold(
                svcRulesMembersSize,
                proposal.mapping.participantIds.size - 1,
              )
            )
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription(
                  s"No party to participant proposal for party $party and participant $newParticipant on domain $domainId"
                )
                .asRuntimeException()
            )
            .asRight
        }
      } else {
        getPartyToParticipant(domainId, party).map(result =>
          Either.cond(
            result.mapping.participants
              .exists(hosting => hosting.participantId == newParticipant),
            result,
            result,
          )
        )
      }
    }

    ensureTopologyProposal[PartyToParticipantX](
      TopologyStoreId.DomainStore(domainId),
      show"Party $party is proposed on $newParticipant",
      proposal => findPartyToParticipant(proposal = proposal),
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
  }

  // TODO(#7884): handle threshold update for sv off-boarding
  def ensurePartyToParticipant(
      domainId: DomainId,
      party: PartyId,
      newParticipant: ParticipantId,
      signedBy: Fingerprint,
      svcRulesMembersSize: Int,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipantX]] = {
    ensureTopologyMapping[PartyToParticipantX](
      TopologyStoreId.DomainStore(domainId),
      show"Party $party is authorized on $newParticipant",
      getPartyToParticipant(domainId, party).map(result =>
        Either.cond(
          result.mapping.participants
            .exists(hosting => hosting.participantId == newParticipant),
          result,
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
  }

  def addTopologyTransactions(
      domainId: Option[DomainId],
      txs: Seq[GenericSignedTopologyTransactionX],
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      TopologyAdminCommandsX.Write
        .AddTransactions(
          txs,
          domainId
            .map(TopologyStoreId.DomainStore(_))
            .getOrElse(TopologyStoreId.AuthorizedStore)
            .filterName,
        )
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
      TopologyStoreId.AuthorizedStore,
      SequencerDomainStateX.create(
        domainId,
        PositiveInt.one,
        active,
        observers,
      ),
      signedBy,
      serial = PositiveInt.one,
      isProposal = false,
    )

  def ensureSequencerDomainState(
      domainId: DomainId,
      newActiveSequencer: SequencerId,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerDomainStateX]] = ensureTopologyMapping[SequencerDomainStateX](
    TopologyStoreId.DomainStore(domainId),
    show"Sequencer $newActiveSequencer is active in SequencerDomainState",
    getSequencerDomainState(domainId).map(result =>
      Either.cond(result.mapping.active.contains(newActiveSequencer), result, result)
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
      TopologyStoreId.AuthorizedStore,
      MediatorDomainStateX.create(
        domain = domainId,
        group = group,
        threshold = PositiveInt.one,
        active = active,
        observers = observers,
      ),
      signedBy,
      serial = PositiveInt.one,
      isProposal = false,
    )

  def ensureMediatorDomainState(
      domainId: DomainId,
      newActiveMediator: MediatorId,
      signedBy: Fingerprint,
      svcRulesMembersSize: Int,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorDomainStateX]] =
    ensureTopologyMapping[MediatorDomainStateX](
      TopologyStoreId.DomainStore(domainId),
      show"Mediator $newActiveMediator is proposed active in MediatorDomainState",
      getMediatorDomainState(domainId).map(result =>
        Either.cond(result.mapping.active.contains(newActiveMediator), result, result)
      ),
      previous =>
        // constructor is not exposed so no copy
        MediatorDomainStateX.create(
          previous.domain,
          previous.group,
          CNThresholds.getMediatorDomainStateThreshold(svcRulesMembersSize, previous.active.size),
          newActiveMediator +: previous.active,
          previous.observers,
        ),
      signedBy,
    )

  def proposeInitialUnionspaceDefinition(
      namespace: Namespace,
      owners: NonEmpty[Set[Namespace]],
      threshold: PositiveInt,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, UnionspaceDefinitionX]] =
    proposeMapping(
      TopologyStoreId.AuthorizedStore,
      UnionspaceDefinitionX.create(
        namespace,
        threshold,
        owners,
      ),
      signedBy = signedBy,
      serial = PositiveInt.one,
      isProposal = false,
    )

  def findUnionspaceDefinitionProposal(
      globalDomain: DomainId,
      unionspace: Namespace,
      newMember: Namespace,
  )(implicit
      traceContext: TraceContext
  ): OptionT[Future, TopologyResult[UnionspaceDefinitionX]] = {
    OptionT(
      listUnionspaceDefinition(globalDomain, unionspace, proposals = true).map(
        _.find(_.mapping.owners.contains(newMember))
      )
    )
  }

  def ensureUnionspaceDefinitionProposal(
      domainId: DomainId,
      unionspace: Namespace,
      newOwner: Namespace,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[UnionspaceDefinitionX]] =
    ensureTopologyProposal[UnionspaceDefinitionX](
      TopologyStoreId.DomainStore(domainId),
      show"Namespace $newOwner is proposed in owners of UnionspaceDefinition",
      proposals =>
        if (proposals)
          listUnionspaceDefinition(domainId, unionspace, proposals).map(
            _.find(_.mapping.owners.contains(newOwner))
              .getOrElse(
                throw Status.NOT_FOUND
                  .withDescription(
                    s"No unionspace proposal found for unionspace $unionspace for new owner $newOwner on domain $domainId"
                  )
                  .asRuntimeException()
              )
              .asRight
          )
        else
          getUnionspaceDefinition(domainId, unionspace).map(result =>
            Either.cond(result.mapping.owners.contains(newOwner), result, result)
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

  def ensureUnionspaceDefinition(
      domainId: DomainId,
      unionspace: Namespace,
      newOwner: Namespace,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[UnionspaceDefinitionX]] =
    ensureTopologyMapping[UnionspaceDefinitionX](
      TopologyStoreId.DomainStore(domainId),
      show"Namespace $newOwner is in owners of UnionspaceDefinition",
      getUnionspaceDefinition(domainId, unionspace).map(result =>
        Either.cond(result.mapping.owners.contains(newOwner), result, result)
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
          TopologyStoreId.DomainStore(domainId),
          TrafficControlStateX
            .create(
              domainId,
              member,
              PositiveLong.tryCreate(newTotalExtraTrafficLimit),
            ),
          signedBy,
          serial = previousOrNone.fold(PositiveInt.one)(_.base.serial + PositiveInt.one),
          isProposal = false,
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
      TopologyStoreId.AuthorizedStore,
      DomainParametersStateX(domainId, parameters),
      signedBy = signedBy,
      serial = PositiveInt.one,
      isProposal = false,
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

  def sign(transactions: Seq[GenericSignedTopologyTransactionX], signedBy: Fingerprint)(implicit
      traceContext: TraceContext
  ): Future[Seq[GenericSignedTopologyTransactionX]] = {
    runCmd(
      TopologyAdminCommandsX.Write.SignTransactions(
        transactions = transactions,
        signedBy = Seq(signedBy),
      )
    )
  }
}

object TopologyAdminConnection {
  private val TOPOLOGY_CHANGE_SKEW: Duration = Duration.ofMillis(100)

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
