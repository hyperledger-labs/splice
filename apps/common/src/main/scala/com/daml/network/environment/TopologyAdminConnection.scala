// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.environment

import cats.data.{EitherT, OptionT}
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.GrpcClientMetrics
import com.daml.network.config.Thresholds
import com.daml.network.environment.RetryProvider.QuietNonRetryableException
import com.daml.network.environment.TopologyAdminConnection.{
  AuthorizedStateChanged,
  TopologyTransactionType,
}
import com.daml.network.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import com.daml.nonempty.NonEmpty
import com.daml.nonempty.catsinstances.*
import com.digitalasset.canton.admin.api.client.commands.{
  DomainTimeCommands,
  TopologyAdminCommandsX,
  VaultAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.topologyx.{
  BaseResult,
  ListOwnerToKeyMappingResult,
}
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig, NonNegativeFiniteDuration}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.crypto.SigningKeyScheme.Ed25519
import com.digitalasset.canton.crypto.SigningPublicKey
import com.digitalasset.canton.crypto.{Fingerprint, PublicKey}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.pretty.PrettyUtil.*
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.time.FetchTimeResponse
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.admin.grpc
import com.digitalasset.canton.topology.admin.grpc.BaseQueryX
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionX,
  TimeQuery,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.store.TimeQuery.HeadState
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.TopologyMappingX
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import io.opentelemetry.api.trace.Tracer
import io.grpc.{Status, StatusRuntimeException}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

/** Connection to nodes that expose topology information (sequencer, mediator, participant)
  */
abstract class TopologyAdminConnection(
    config: ClientConfig,
    apiLoggingConfig: ApiLoggingConfig,
    loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
    override protected[this] val retryProvider: RetryProvider,
)(implicit ec: ExecutionContextExecutor, tracer: Tracer)
    extends AppConnection(
      config,
      apiLoggingConfig,
      loggerFactory,
      grpcClientMetrics,
    )
    with RetryProvider.Has
    with Spanning {
  import TopologyAdminConnection.TopologyResult
  import TopologyAdminConnection.RecreateOnAuthorizedStateChange

  override val serviceName = "Canton Participant Admin API"

  private val memberIdO: AtomicReference[Option[UniqueIdentifier]] = new AtomicReference(None)

  def getId(
  )(implicit traceContext: TraceContext): Future[UniqueIdentifier] =
    // We don't lock around this, worst case we make concurrent requests
    // that both update memberIdO which is fine as the request is idempotent.
    memberIdO.get() match {
      case Some(memberId) => Future.successful(memberId)
      case None =>
        for {
          memberId <- runCmd(
            TopologyAdminCommandsX.Init.GetId()
          )
        } yield {
          memberIdO.set(Some(memberId))
          memberId
        }
    }

  def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean]

  /*
   * @param The maximal time that we allow for the domain time lower-bound to lag wrt our wall-clock when reading it.
   *  Use this to balance speed of the domain-time lower-bound advancing with the load created on the domain to fetch a
   *  fresh domain-time proof.
   */
  def getDomainTimeLowerBound(
      domainId: DomainId,
      maxDomainTimeLag: NonNegativeFiniteDuration,
  )(implicit traceContext: TraceContext): Future[FetchTimeResponse] =
    runCmd(
      DomainTimeCommands.FetchTime(
        Some(domainId),
        freshnessBound =
          com.digitalasset.canton.time.NonNegativeFiniteDuration.fromConfig(maxDomainTimeLag),
        timeout = retryProvider.timeouts.default,
      )
    )

  def listPartyToParticipant(
      filterStore: String = "",
      operation: Option[TopologyChangeOpX] = None,
      filterParty: String = "",
      filterParticipant: String = "",
      timeQuery: TimeQuery = TimeQuery.HeadState,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipantX]]] = {
    runCmd(
      TopologyAdminCommandsX.Read.ListPartyToParticipant(
        BaseQueryX(
          filterStore,
          proposals = proposals.proposals,
          timeQuery,
          operation,
          filterSigningKey = proposals.signingKey.getOrElse(""),
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
        filterStore = TopologyStoreId.DomainStore(domainId).filterName,
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

  def listSequencerDomainState(
      domainId: DomainId,
      timeQuery: TimeQuery,
      proposals: Boolean = false,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[SequencerDomainStateX]]] =
    listSequencerDomainState(
      TopologyStoreId.DomainStore(domainId),
      domainId,
      timeQuery,
      proposals,
    )

  def listSequencerDomainState(
      store: TopologyStoreId,
      domainId: DomainId,
      timeQuery: TimeQuery,
      proposals: Boolean,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[SequencerDomainStateX]]] =
    runCmd(
      TopologyAdminCommandsX.Read.SequencerDomainState(
        BaseQueryX(
          filterStore = store.filterName,
          proposals = proposals,
          timeQuery = timeQuery,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterDomain = domainId.filterString,
      )
    ).map { txs =>
      txs.map(res => TopologyResult(res.context, res.item))
    }

  def getSequencerDomainState(domainId: DomainId, proposals: Boolean = false)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerDomainStateX]] =
    listSequencerDomainState(domainId, HeadState, proposals).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No sequencer state for domain $domainId")
            .asRuntimeException()
        )
    }

  def getMediatorDomainStateProposals(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[MediatorDomainStateX]]] = {
    listMediatorDomainState(TopologyStoreId.DomainStore(domainId), domainId, proposals = true)
  }

  def getMediatorDomainState(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorDomainStateX]] =
    listMediatorDomainState(TopologyStoreId.DomainStore(domainId), domainId, proposals = false)
      .map { txs =>
        txs.headOption
          .getOrElse(
            throw Status.NOT_FOUND
              .withDescription(s"No mediator state for domain $domainId")
              .asRuntimeException()
          )
      }

  def listMediatorDomainState(store: TopologyStoreId, domainId: DomainId, proposals: Boolean)(
      implicit traceContext: TraceContext
  ) = {
    runCmd(
      TopologyAdminCommandsX.Read.MediatorDomainState(
        BaseQueryX(
          filterStore = store.filterName,
          proposals = proposals,
          timeQuery = TimeQuery.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterDomain = domainId.filterString,
      )
    )
  }.map { txs =>
    txs.map { tx => TopologyResult(tx.context, tx.item) }
  }

  def getDecentralizedNamespaceDefinition(
      domainId: DomainId,
      decentralizedNamespace: Namespace,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DecentralizedNamespaceDefinitionX]] =
    listDecentralizedNamespaceDefinition(domainId, decentralizedNamespace).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(
              show"No decentralized namespace definition for $decentralizedNamespace on domain $domainId"
            )
            .asRuntimeException()
        )
    }

  def listDecentralizedNamespaceDefinition(
      domainId: DomainId,
      decentralizedNamespace: Namespace,
      proposals: TopologyTransactionType = AuthorizedState,
      timeQuery: TimeQuery = TimeQuery.HeadState,
  )(implicit tc: TraceContext) = {
    runCmd(
      TopologyAdminCommandsX.Read.ListDecentralizedNamespaceDefinition(
        BaseQueryX(
          filterStore = TopologyStoreId.DomainStore(domainId).filterName,
          proposals = proposals.proposals,
          timeQuery = timeQuery,
          ops = None,
          filterSigningKey = proposals.signingKey.getOrElse(""),
          protocolVersion = None,
        ),
        filterNamespace = decentralizedNamespace.toProtoPrimitive,
      )
    ).map {
      _.map(result => TopologyResult(result.context, result.item))
    }
  }

  def getIdentityTransactions(
      id: UniqueIdentifier,
      store: TopologyStoreId,
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransactionX]] =
    listAllTransactions(
      store,
      includeMappings = Set(
        TopologyMappingX.Code.NamespaceDelegationX,
        TopologyMappingX.Code.OwnerToKeyMappingX,
        TopologyMappingX.Code.IdentifierDelegationX,
      ),
      filterNamespace = Some(id.namespace),
    ).map(_.map(_.transaction))

  def listAllTransactions(
      store: TopologyStoreId,
      timeQuery: TimeQuery = TimeQuery.HeadState,
      proposals: Boolean = false,
      includeMappings: Set[TopologyMappingX.Code] = Set.empty,
      filterNamespace: Option[Namespace] = None,
  )(implicit
      tc: TraceContext
  ): Future[Seq[StoredTopologyTransactionX[TopologyChangeOpX, TopologyMappingX]]] = {
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        query = BaseQueryX(
          filterStore = store.filterName,
          proposals = proposals,
          timeQuery = timeQuery,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterNamespace = filterNamespace.fold("")(_.filterString),
        excludeMappings =
          if (includeMappings.isEmpty) Seq.empty
          else TopologyMappingX.Code.all.diff(includeMappings.toSeq).map(_.code),
      )
    ).map(_.result)
  }

  def ensureInitialOwnerToKeyMapping(
      member: Member,
      keys: NonEmpty[Seq[PublicKey]],
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.ensureThatB(
      retryFor,
      "ensure_initial_owner_to_key_mapping",
      show"Initial key mapping for $member exists",
      listOwnerToKeyMapping(
        member
      ).map(_.nonEmpty),
      proposeInitialOwnerToKeyMapping(member, keys, signedBy).map(_ => ()),
      logger,
    )

  private def proposeInitialOwnerToKeyMapping(
      member: Member,
      keys: NonEmpty[Seq[PublicKey]],
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, OwnerToKeyMappingX]] =
    proposeMapping(
      TopologyStoreId.AuthorizedStore,
      OwnerToKeyMappingX(
        member,
        domain = None,
        keys = keys,
      ),
      signedBy = signedBy,
      serial = PositiveInt.one,
      isProposal = false,
    )

  private def listOwnerToKeyMapping(member: Member)(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[OwnerToKeyMappingX]]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListOwnerToKeyMapping(
        BaseQueryX(
          filterStore = AuthorizedStore.filterName,
          proposals = false,
          timeQuery = TimeQuery.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterKeyOwnerType = None,
        filterKeyOwnerUid = member.uid.toProtoPrimitive,
      )
    ).map { txs =>
      txs.map { case ListOwnerToKeyMappingResult(base, mapping) =>
        TopologyResult(base, mapping)
      }
    }

  private def exportTopologySnapshot(
      store: TopologyStoreId,
      proposals: Boolean,
      excludeMappings: Seq[TopologyMappingX.Code],
      filterNamespace: String,
  )(implicit
      traceContext: TraceContext
  ): Future[ByteString] = {
    runCmd(
      TopologyAdminCommandsX.Read.ExportTopologySnapshot(
        BaseQueryX(
          filterStore = store.filterName,
          proposals = proposals,
          timeQuery = TimeQuery.Range(from = None, until = None),
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterNamespace = filterNamespace,
        excludeMappings = excludeMappings.map(_.code),
      )
    )
  }

  def importTopologySnapshot(
      topologyTransactions: ByteString,
      store: TopologyStoreId,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(
      TopologyAdminCommandsX.Write.ImportTopologySnapshot(topologyTransactions, store.filterName)
    )
  }

  def exportAuthorizedStoreSnapshot(
      participantId: UniqueIdentifier
  )(implicit
      tc: TraceContext
  ): Future[ByteString] =
    exportTopologySnapshot(
      TopologyStoreId.AuthorizedStore,
      proposals = false,
      excludeMappings = TopologyMappingX.Code.all.diff(
        Seq(
          TopologyMappingX.Code.NamespaceDelegationX,
          TopologyMappingX.Code.OwnerToKeyMappingX,
          TopologyMappingX.Code.IdentifierDelegationX,
          // only relevant for participants
          TopologyMappingX.Code.VettedPackagesX,
        )
      ),
      filterNamespace = participantId.namespace.filterString,
    )

  def proposeMapping[M <: TopologyMappingX: ClassTag](
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

  /** Version of [[ensureTopologyMapping]] that also handles proposals:
    * - a new topology transaction is created as a proposal
    * - checks the proposals as well to see if the check holds
    */
  private def ensureTopologyProposal[M <: TopologyMappingX: ClassTag](
      store: TopologyStoreId,
      description: String,
      check: TopologyTransactionType => EitherT[Future, TopologyResult[M], TopologyResult[M]],
      update: M => Either[String, M],
      retryFor: RetryFor,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[TopologyResult[M]] = {
    ensureTopologyMapping(
      store,
      s"proposal $description",
      check(TopologyTransactionType.AuthorizedState)
        .leftFlatMap { authorizedState =>
          EitherT(
            check(TopologyTransactionType.ProposalSignedBy(signedBy))
              .leftMap(_ => authorizedState)
              .value
              .recover {
                case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
                  Left(authorizedState)
              }
          )
        },
      update,
      retryFor,
      signedBy,
      isProposal = true,
    )
  }

  /** Ensure that either the accepted state passes the check, or a topology mapping is created that passes the check
    *  - run the check to see if it holds
    *  - if not then create transaction with the updated mapping
    *  - run check until it holds or until the current state returned by the check has a serial that is newer than the state when we last created the new mapping
    *  - if a newer state is returned by the check then re-create the mapping with a new serial and go to the previous step of running the check
    *
    *  This type of re-create of the topology transactions is required to ensure that updating the same topology state in parallel will eventually succeed for all the updates
    */
  def ensureTopologyMapping[M <: TopologyMappingX: ClassTag](
      store: TopologyStoreId,
      description: String,
      check: => EitherT[Future, TopologyResult[M], TopologyResult[M]],
      update: M => Either[String, M],
      retryFor: RetryFor,
      signedBy: Fingerprint,
      isProposal: Boolean = false,
      recreateOnAuthorizedStateChange: RecreateOnAuthorizedStateChange =
        RecreateOnAuthorizedStateChange.Recreate,
  )(implicit traceContext: TraceContext): Future[TopologyResult[M]] = {
    withSpan("establish_topology_mapping") { implicit traceContext => _ =>
      logger.info(s"Ensuring that $description")
      retryProvider
        .retry(
          retryFor,
          "establish_topology_mapping_retry",
          description,
          check.foldF(
            { case TopologyResult(beforeEstablishedBaseResult, mapping) =>
              (recreateOnAuthorizedStateChange match {
                case RecreateOnAuthorizedStateChange.Abort(expectedSerial)
                    if expectedSerial != beforeEstablishedBaseResult.serial =>
                  Future.failed(AuthorizedStateChanged(beforeEstablishedBaseResult.serial))
                case _ => Future.unit
              })
                .flatMap { _ =>
                  val updatedMapping = update(mapping)
                  proposeMapping(
                    store,
                    updatedMapping,
                    signedBy,
                    serial = beforeEstablishedBaseResult.serial + PositiveInt.one,
                    isProposal = isProposal,
                  )
                }
                .flatMap { _ =>
                  logger.debug(
                    s"Submitted proposal for $description, waiting until the proposal gets accepted"
                  )
                  retryProvider.retry(
                    retryFor,
                    "check_establish_topology_mapping",
                    s"check established $description",
                    check.leftMap { currentAuthorizedState =>
                      if (
                        currentAuthorizedState.base.serial == beforeEstablishedBaseResult.serial
                      ) {
                        Status.FAILED_PRECONDITION
                          .withDescription("Condition is not yet observed.")
                          .asRuntimeException()
                      } else {
                        AuthorizedStateChanged(
                          currentAuthorizedState.base.serial
                        )
                      }
                    }.rethrowT,
                    logger,
                  )
                }
                .recover {
                  case ex: AuthorizedStateChanged
                      if recreateOnAuthorizedStateChange.shouldRecreate =>
                    logger.info(
                      s"check $description failed and the base state has changed, re-establishing condition"
                    )
                    throw Status.FAILED_PRECONDITION
                      .withDescription(ex.getMessage)
                      .asRuntimeException()
                }
            },
            Future.successful,
          ),
          logger,
        )
        .transform(
          result => {
            logger.info(s"Success: $description")
            result
          },
          exception => {
            if (isClosing)
              logger.info(s"Gave up ensuring $description, as we are shutting down")
            else
              logger.info(s"Gave up ensuring $description", exception)
            exception
          },
        )
    }
  }

  def proposeInitialPartyToParticipant(
      store: TopologyStoreId,
      partyId: PartyId,
      participantId: ParticipantId,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, PartyToParticipantX]] = {
    proposeInitialPartyToParticipant(store, partyId, Seq(participantId), signedBy)
  }
  def proposeInitialPartyToParticipant(
      store: TopologyStoreId,
      partyId: PartyId,
      participants: Seq[ParticipantId],
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, PartyToParticipantX]] =
    proposeMapping(
      store,
      PartyToParticipantX(
        partyId,
        None,
        PositiveInt.one,
        participants.map(
          HostingParticipant(
            _,
            ParticipantPermission.Submission,
          )
        ),
        groupAddressing = false,
      ),
      signedBy = signedBy,
      serial = PositiveInt.one,
      isProposal = false,
    )

  def ensurePartyToParticipantRemovalProposal(
      domainId: DomainId,
      party: PartyId,
      participantToRemove: ParticipantId,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[PartyToParticipantX]] = {
    def removeParticipant(participants: Seq[HostingParticipant]): Seq[HostingParticipant] = {
      participants.filterNot(_.participantId == participantToRemove)
    }
    ensurePartyToParticipantProposal(
      s"Party $party is proposed to be removed from $participantToRemove",
      domainId,
      party,
      removeParticipant,
      signedBy,
    )
  }

  def ensurePartyToParticipantAdditionProposal(
      domainId: DomainId,
      party: PartyId,
      newParticipant: ParticipantId,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipantX]] = {
    def addParticipant(participants: Seq[HostingParticipant]): Seq[HostingParticipant] = {
      // New participants are only given Observation rights. We explicitly promote them to Submission rights later.
      // See SvOnboardingPromoteToSubmitterTrigger.
      val newHostingParticipant =
        HostingParticipant(newParticipant, ParticipantPermission.Observation)
      if (participants.map(_.participantId).contains(newHostingParticipant.participantId)) {
        participants
      } else {
        participants.appended(newHostingParticipant)
      }
    }
    ensurePartyToParticipantProposal(
      s"Party $party is proposed to be added on $newParticipant",
      domainId,
      party,
      addParticipant,
      signedBy,
    )
  }

  def ensurePartyToParticipantAdditionProposalWithSerial(
      domainId: DomainId,
      party: PartyId,
      newParticipant: ParticipantId,
      expectedSerial: PositiveInt,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipantX]] = {
    ensureTopologyMapping[PartyToParticipantX](
      TopologyStoreId.DomainStore(domainId),
      show"Party $party is authorized on $newParticipant",
      EitherT(
        getPartyToParticipant(domainId, party)
          .map(result =>
            Either
              .cond(
                result.mapping.participants
                  .exists(hosting => hosting.participantId == newParticipant),
                result,
                result,
              )
          )
      ),
      previous => {
        val newHostingParticipants = previous.participants.appended(
          HostingParticipant(
            newParticipant,
            ParticipantPermission.Observation,
          )
        )
        Right(
          previous.copy(
            participants = newHostingParticipants,
            threshold = Thresholds
              .partyToParticipantThreshold(newHostingParticipants),
          )
        )
      },
      RetryFor.ClientCalls,
      signedBy,
      isProposal = true,
      recreateOnAuthorizedStateChange = RecreateOnAuthorizedStateChange.Abort(expectedSerial),
    )
  }

  // the participantChange participant sequence must be ordering, if not canton will consider topology proposals with different ordering as fully different proposals and will not aggregate signatures
  private def ensurePartyToParticipantProposal(
      description: String,
      domainId: DomainId,
      party: PartyId,
      participantChange: Seq[HostingParticipant] => Seq[
        HostingParticipant
      ], // participantChange must be idempotent
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipantX]] = {
    def findPartyToParticipant(topologyTransactionType: TopologyTransactionType) = EitherT {
      topologyTransactionType match {
        case proposals @ (TopologyTransactionType.ProposalSignedBy(_) |
            TopologyTransactionType.AllProposals) =>
          listPartyToParticipant(
            filterStore = domainId.filterString,
            filterParty = party.filterString,
            proposals = proposals,
          ).map { proposals =>
            proposals
              .find(proposal => {
                val newHostingParticipants = participantChange(
                  proposal.mapping.participants
                )
                proposal.mapping.participantIds ==
                  newHostingParticipants.map(_.participantId)
                  && proposal.mapping.threshold == Thresholds.partyToParticipantThreshold(
                    newHostingParticipants
                  )
              })
              .getOrElse(
                throw Status.NOT_FOUND
                  .withDescription(
                    s"No party to participant proposal for party $party on domain $domainId"
                  )
                  .asRuntimeException()
              )
              .asRight
          }
        case TopologyTransactionType.AuthorizedState =>
          getPartyToParticipant(domainId, party).map(result => {
            val newHostingParticipants = participantChange(
              result.mapping.participants
            )
            Either.cond(
              result.mapping.participantIds ==
                newHostingParticipants.map(_.participantId),
              result,
              result,
            )
          })
      }
    }

    ensureTopologyProposal[PartyToParticipantX](
      TopologyStoreId.DomainStore(domainId),
      description,
      queryType => findPartyToParticipant(queryType),
      previous => {
        val newHostingParticipants = participantChange(previous.participants)
        Right(
          previous.copy(
            participants = newHostingParticipants,
            threshold = Thresholds.partyToParticipantThreshold(newHostingParticipants),
          )
        )
      },
      RetryFor.WaitingOnInitDependency,
      signedBy,
    )
  }

  def ensureHostingParticipantIsPromotedToSubmitter(
      domainId: DomainId,
      party: PartyId,
      participantId: ParticipantId,
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipantX]] = {
    def promoteParticipantToSubmitter(
        participants: Seq[HostingParticipant]
    ): Seq[HostingParticipant] = {
      val newValue = HostingParticipant(participantId, ParticipantPermission.Submission)
      val oldIndex = participants.indexWhere(_.participantId == newValue.participantId)
      participants.updated(oldIndex, newValue)
    }

    ensureTopologyMapping[PartyToParticipantX](
      TopologyStoreId.DomainStore(domainId),
      s"Participant $participantId is promoted to have Submission permission for party $party",
      EitherT(getPartyToParticipant(domainId, party).map(result => {
        Either.cond(
          result.mapping.participants
            .contains(HostingParticipant(participantId, ParticipantPermission.Submission)),
          result,
          result,
        )
      })),
      previous => {
        Either.cond(
          previous.participants.exists(_.participantId == participantId), {
            val newHostingParticipants = promoteParticipantToSubmitter(previous.participants)
            previous.copy(
              participants = newHostingParticipants,
              threshold = Thresholds.partyToParticipantThreshold(newHostingParticipants),
            )
          },
          show"Participant $participantId does not host party $party",
        )
      },
      retryFor,
      signedBy,
      isProposal = true,
    )
  }

  def addTopologyTransactions(
      store: TopologyStoreId,
      txs: Seq[GenericSignedTopologyTransactionX],
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      TopologyAdminCommandsX.Write
        .AddTransactions(
          txs,
          store.filterName,
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

  def ensureSequencerDomainStateAddition(
      domainId: DomainId,
      newActiveSequencer: SequencerId,
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerDomainStateX]] = {
    def sequencerChange(sequencers: Seq[SequencerId]): Seq[SequencerId] = {
      val newSequencers = sequencers :+ newActiveSequencer
      newSequencers.distinct
    }

    ensureSequencerDomainState(
      s"Add sequencer $newActiveSequencer",
      domainId,
      sequencerChange,
      signedBy,
      retryFor,
    )
  }

  def ensureSequencerDomainStateRemoval(
      domainId: DomainId,
      sequencerToRemove: SequencerId,
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerDomainStateX]] = {
    def sequencerChange(sequencers: Seq[SequencerId]): Seq[SequencerId] = {
      sequencers.filterNot(_ == sequencerToRemove)
    }

    ensureSequencerDomainState(
      s"Remove sequencer $sequencerToRemove",
      domainId,
      sequencerChange,
      signedBy,
      retryFor,
    )
  }

  private def ensureSequencerDomainState(
      description: String,
      domainId: DomainId,
      sequencerChange: Seq[SequencerId] => Seq[SequencerId],
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerDomainStateX]] = {
    ensureTopologyMapping[SequencerDomainStateX](
      TopologyStoreId.DomainStore(domainId),
      description,
      EitherT(
        getSequencerDomainState(domainId).map(result => {
          val newSequencers = sequencerChange(result.mapping.active)
          // we need to check the threshold as well because we reset it to 1 in tests (see ResetSequencerDomainStateThreshold)
          val newThreshold = Thresholds.sequencerConnectionsSizeThreshold(newSequencers.size)
          Either
            .cond(
              result.mapping.active.forgetNE == newSequencers && result.mapping.threshold == newThreshold,
              result,
              result,
            )
        })
      ),
      previous => {
        val newSequencers = sequencerChange(previous.active)
        logger.debug(
          s"Applying sequencer change: previous [${previous.active}], wanted [$newSequencers]"
        )
        // The threshold in here is used by Canton traffic control
        SequencerDomainStateX.create(
          previous.domain,
          Thresholds
            .sequencerConnectionsSizeThreshold(newSequencers.size),
          newSequencers,
          previous.observers,
        )
      },
      retryFor,
      signedBy,
      isProposal = true,
    )
  }

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

  def ensureMediatorDomainStateAdditionProposal(
      domainId: DomainId,
      newActiveMediator: MediatorId,
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorDomainStateX]] = {
    def mediatorChange(mediators: Seq[MediatorId]): Seq[MediatorId] = {
      val newMediators = mediators :+ newActiveMediator
      newMediators.distinct
    }
    ensureMediatorDomainState(
      s"Add mediator $newActiveMediator",
      domainId,
      mediatorChange,
      signedBy,
      retryFor,
    )
  }

  def ensureMediatorDomainStateRemovalProposal(
      domainId: DomainId,
      mediatorToRemove: MediatorId,
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorDomainStateX]] = {
    def mediatorChange(mediators: Seq[MediatorId]): Seq[MediatorId] = {
      mediators.filterNot(_ == mediatorToRemove)
    }
    ensureMediatorDomainState(
      s"Remove mediator $mediatorToRemove",
      domainId,
      mediatorChange,
      signedBy,
      retryFor,
    )
  }

  private def ensureMediatorDomainState(
      description: String,
      domainId: DomainId,
      mediatorChange: Seq[MediatorId] => Seq[MediatorId],
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorDomainStateX]] =
    ensureTopologyMapping[MediatorDomainStateX](
      TopologyStoreId.DomainStore(domainId),
      description,
      EitherT(
        getMediatorDomainState(domainId).map(result =>
          Either
            .cond(
              result.mapping.active.forgetNE == mediatorChange(result.mapping.active),
              result,
              result,
            )
        )
      ),
      previous => {
        val newMediators = mediatorChange(previous.active)
        logger.debug(
          s"Applying mediator change: previous [${previous.active}], wanted [$newMediators]"
        )
        // constructor is not exposed so no copy
        MediatorDomainStateX.create(
          previous.domain,
          previous.group,
          Thresholds
            .mediatorDomainStateThreshold(newMediators.size),
          newMediators,
          previous.observers,
        )
      },
      retryFor,
      signedBy,
      isProposal = true,
    )

  def proposeInitialDecentralizedNamespaceDefinition(
      namespace: Namespace,
      owners: NonEmpty[Set[Namespace]],
      threshold: PositiveInt,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, DecentralizedNamespaceDefinitionX]] =
    proposeMapping(
      TopologyStoreId.AuthorizedStore,
      DecentralizedNamespaceDefinitionX.create(
        namespace,
        threshold,
        owners,
      ),
      signedBy = signedBy,
      serial = PositiveInt.one,
      isProposal = false,
    )

  def ensureDecentralizedNamespaceDefinitionProposalAccepted(
      domainId: DomainId,
      decentralizedNamespace: Namespace,
      newOwner: Namespace,
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DecentralizedNamespaceDefinitionX]] =
    ensureDecentralizedNamespaceDefinitionOwnerChangeProposalAccepted(
      show"Namespace $newOwner is in owners of DecentralizedNamespaceDefinition",
      domainId,
      decentralizedNamespace,
      _.incl(newOwner),
      signedBy,
      retryFor,
    )

  def ensureDecentralizedNamespaceDefinitionRemovalProposal(
      domainId: DomainId,
      decentralizedNamespace: Namespace,
      ownerToRemove: Namespace,
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DecentralizedNamespaceDefinitionX]] =
    ensureDecentralizedNamespaceDefinitionOwnerChangeProposalAccepted(
      show"Namespace $ownerToRemove was removed from the decentralized namespace definition",
      domainId,
      decentralizedNamespace,
      owners =>
        NonEmpty
          .from(owners.excl(ownerToRemove))
          .getOrElse(
            throw Status.UNKNOWN
              .withDescription(
                s"$ownerToRemove is not an owner or decentralized namespace has only 1 owner in decentralized namespace $decentralizedNamespace "
              )
              .asRuntimeException()
          ),
      signedBy,
      retryFor,
    )

  def ensureDecentralizedNamespaceDefinitionOwnerChangeProposalAccepted(
      description: String,
      domainId: DomainId,
      decentralizedNamespace: Namespace,
      ownerChange: NonEmpty[Set[Namespace]] => NonEmpty[Set[Namespace]],
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DecentralizedNamespaceDefinitionX]] =
    ensureTopologyMapping[DecentralizedNamespaceDefinitionX](
      TopologyStoreId.DomainStore(domainId),
      description,
      decentralizedNamespaceDefinitionForNamespace(domainId, decentralizedNamespace, ownerChange),
      previous => {
        // constructor is not exposed so no copy
        val newOwners = ownerChange(previous.owners)
        DecentralizedNamespaceDefinitionX.create(
          previous.namespace,
          Thresholds.decentralizedNamespaceThreshold(
            newOwners.size
          ),
          newOwners,
        )
      },
      retryFor,
      signedBy,
      isProposal = true,
    )

  private def decentralizedNamespaceDefinitionForNamespace(
      domainId: DomainId,
      decentralizedNamespace: Namespace,
      ownerChange: NonEmpty[Set[Namespace]] => NonEmpty[Set[Namespace]],
  )(implicit tc: TraceContext) = {
    EitherT(
      getDecentralizedNamespaceDefinition(domainId, decentralizedNamespace).map(result =>
        Either.cond(result.mapping.owners == ownerChange(result.mapping.owners), result, result)
      )
    )
  }

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

  def ensureDomainParameters(
      domainId: DomainId,
      parametersBuilder: DynamicDomainParameters => DynamicDomainParameters,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DomainParametersStateX]] =
    ensureTopologyMapping[DomainParametersStateX](
      TopologyStoreId.DomainStore(domainId),
      "update dynamic domain parameters",
      EitherT(
        getDomainParametersState(domainId).map(state =>
          Either.cond(
            state.mapping.parameters == parametersBuilder(state.mapping.parameters),
            state,
            state,
          )
        )
      ),
      previous => Right(previous.copy(parameters = parametersBuilder(previous.parameters))),
      signedBy = signedBy,
      retryFor = RetryFor.ClientCalls,
      isProposal = true,
    )

  def getDomainParametersState(
      domainId: DomainId,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit tc: TraceContext): Future[TopologyResult[DomainParametersStateX]] = {
    listDomainParametersState(
      TopologyStoreId.DomainStore(domainId),
      domainId,
      proposals,
      TimeQuery.HeadState,
    )
      .map(_.headOption.getOrElse {
        throw Status.NOT_FOUND
          .withDescription(s"No DomainParametersState state domain $domainId")
          .asRuntimeException
      })
  }

  def listDomainParametersState(
      domainId: DomainId,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[DomainParametersStateX]]] = {
    listDomainParametersState(
      TopologyStoreId.DomainStore(domainId),
      domainId,
      proposals,
      TimeQuery.Range(None, None),
    )
  }

  def listDomainParametersState(
      storeId: TopologyStoreId,
      domainId: DomainId,
      proposals: TopologyTransactionType,
      timeQuery: TimeQuery,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[DomainParametersStateX]]] = {
    runCmd(
      TopologyAdminCommandsX.Read.DomainParametersState(
        BaseQueryX(
          storeId.filterName,
          proposals = proposals.proposals,
          timeQuery,
          None,
          filterSigningKey = proposals.signingKey.getOrElse(""),
          protocolVersion = None,
        ),
        domainId.filterString,
      )
    ).map(_.map(r => TopologyResult(r.context, DomainParametersStateX(domainId, r.item))))
  }

  def initId(id: NodeIdentity)(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(TopologyAdminCommandsX.Init.InitId(id.uid.toProtoPrimitive))
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

  def identity()(implicit
      traceContext: TraceContext
  ): Future[NodeIdentity]

  def listMyKeys(name: String = "")(implicit
      traceContext: TraceContext
  ): Future[Seq[com.digitalasset.canton.crypto.admin.grpc.PrivateKeyMetadata]] = {
    runCmd(VaultAdminCommands.ListMyKeys("", name))
  }

  def exportKeyPair(fingerprint: Fingerprint)(implicit
      traceContext: TraceContext
  ): Future[ByteString] = {
    runCmd(VaultAdminCommands.ExportKeyPair(fingerprint, ProtocolVersion.latest, password = None))
  }

  def generateKeyPair(name: String)(implicit
      traceContext: TraceContext
  ): Future[SigningPublicKey] = {
    runCmd(VaultAdminCommands.GenerateSigningKey(name, Some(Ed25519)))
  }

  def importKeyPair(keyPair: Array[Byte], name: Option[String])(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(VaultAdminCommands.ImportKeyPair(ByteString.copyFrom(keyPair), name, password = None))
  }

}

object TopologyAdminConnection {
  sealed trait TopologyTransactionType {
    def proposals: Boolean
    def signingKey: Option[String]
  }

  object TopologyTransactionType {

    case object AllProposals extends TopologyTransactionType {
      override def proposals: Boolean = true

      override def signingKey: Option[String] = None
    }

    case class ProposalSignedBy(signature: Fingerprint) extends TopologyTransactionType {
      override def proposals: Boolean = true

      override def signingKey: Option[String] = Some(signature.toProtoPrimitive)
    }

    case object AuthorizedState extends TopologyTransactionType {
      override def proposals: Boolean = false

      override def signingKey: Option[String] = None
    }

  }

  sealed abstract class RecreateOnAuthorizedStateChange {
    def shouldRecreate: Boolean
  }

  object RecreateOnAuthorizedStateChange {
    final case object Recreate extends RecreateOnAuthorizedStateChange {
      override def shouldRecreate = true
    }
    final case class Abort(expectedSerial: PositiveInt) extends RecreateOnAuthorizedStateChange {
      override def shouldRecreate = false
    }
  }

  final case class AuthorizedStateChanged(baseSerial: PositiveInt)
      extends QuietNonRetryableException(s"Authorized state changed, new base serial $baseSerial")

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

  implicit val prettyStore: Pretty[grpc.TopologyStore] = prettyOfObject[grpc.TopologyStore]

  implicit val prettyBaseResult: Pretty[BaseResult] =
    prettyNode(
      "BaseResult",
      param("store", _.store),
      param("validFrom", _.validFrom),
      param("validUntil", _.validUntil),
      param("operation", _.operation),
      param("transactionHash", _.transactionHash),
      param("serial", _.serial),
      param("signedBy", _.signedBy),
    )
}
