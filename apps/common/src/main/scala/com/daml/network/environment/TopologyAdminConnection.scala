package com.daml.network.environment

import cats.data.{EitherT, OptionT}
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.config.CNThresholds
import com.daml.network.environment.RetryProvider.QuietNonRetryableException
import com.daml.network.environment.TopologyAdminConnection.{
  AuthorizedStateChanged,
  TopologyTransactionType,
}
import com.daml.network.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import com.daml.nonempty.NonEmpty
import com.daml.nonempty.catsinstances.*
import com.digitalasset.canton.admin.api.client.commands.{
  TopologyAdminCommandsX,
  VaultAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.topologyx.{
  BaseResult,
  ListOwnerToKeyMappingResult,
  ListSequencerDomainStateResult,
}
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt, PositiveLong}
import com.digitalasset.canton.crypto.{Fingerprint, PublicKey}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.pretty.PrettyUtil.*
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.time.{Clock, RemoteClock, WallClock}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.admin.grpc
import com.digitalasset.canton.topology.admin.grpc.BaseQueryX
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionX,
  TimeQueryX,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.TopologyMappingX
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}

import java.time.{Duration, Instant}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

/** Connection to nodes that expose topology information (sequencer, mediator, participant)
  */
abstract class TopologyAdminConnection(
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

  def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean]

  def listPartyToParticipant(
      filterStore: String = "",
      operation: Option[TopologyChangeOpX] = None,
      filterParty: String = "",
      filterParticipant: String = "",
      timeQuery: TimeQueryX = TimeQueryX.HeadState,
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

  def getMediatorDomainStateProposals(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[MediatorDomainStateX]]] = {
    listMediatorDomainState(domainId, proposals = true)
  }

  def getMediatorDomainState(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorDomainStateX]] =
    listMediatorDomainState(domainId, proposals = false).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No mediator state for domain $domainId")
            .asRuntimeException()
        )
    }

  private def listMediatorDomainState(domainId: DomainId, proposals: Boolean)(implicit
      traceContext: TraceContext
  ) = {
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

  private def listDecentralizedNamespaceDefinition(
      domainId: DomainId,
      decentralizedNamespace: Namespace,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit tc: TraceContext) = {
    runCmd(
      TopologyAdminCommandsX.Read.ListDecentralizedNamespaceDefinition(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = proposals.proposals,
          timeQuery = TimeQueryX.HeadState,
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
      domainId: Option[DomainId],
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransactionX]] =
    getTransactions(
      Set(TopologyMappingX.Code.NamespaceDelegationX, TopologyMappingX.Code.OwnerToKeyMappingX),
      Some(id),
      domainId,
    )

  def listAllTransactions(
      store: Option[TopologyStoreId],
      timeQuery: TimeQueryX = TimeQueryX.HeadState,
      proposals: Boolean = false,
  )(implicit
      tc: TraceContext
  ): Future[Seq[StoredTopologyTransactionX[TopologyChangeOpX, TopologyMappingX]]] = {
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = store.map(_.filterName).getOrElse(""),
          proposals = proposals,
          timeQuery = timeQuery,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        )
      )
    ).map(_.result)
  }

  private def getTransactions(
      transactionType: Set[TopologyMappingX.Code],
      id: Option[UniqueIdentifier],
      domainId: Option[DomainId],
      proposals: Boolean = false,
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransactionX]] =
    listAllTransactions(domainId.map(TopologyStoreId.DomainStore(_)), proposals = proposals)
      .map(_.map(_.transaction))
      .map { transactions =>
        transactions
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

  def getIdentityBootstrapTransactions(domainId: Option[DomainId], id: UniqueIdentifier)(implicit
      traceContext: TraceContext
  ): Future[Seq[GenericSignedTopologyTransactionX]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = domainId.map(_.filterString).getOrElse(AuthorizedStore.filterName),
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
          Set(
            TopologyMappingX.Code.NamespaceDelegationX,
            TopologyMappingX.Code.OwnerToKeyMappingX,
            TopologyMappingX.Code.IdentifierDelegationX,
          )
            .contains(tx.transaction.mapping.code)
        )
    }

  def ensureInitialOwnerToKeyMapping(
      member: Member,
      keys: NonEmpty[Seq[PublicKey]],
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.ensureThatB(
      retryFor,
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
          timeQuery = TimeQueryX.HeadState,
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

  def getTopologySnapshot(
      domainId: DomainId,
      from: Option[CantonTimestamp] = None,
      until: Option[CantonTimestamp] = None,
  )(implicit
      traceContext: TraceContext
  ): Future[GenericStoredTopologyTransactionsX] = {
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = false,
          timeQuery = TimeQueryX.Range(from, until),
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        )
      )
    )
  }

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
      recreateOnAuthorizedStateChange: Boolean = true,
  )(implicit traceContext: TraceContext): Future[TopologyResult[M]] =
    retryProvider.retry(
      retryFor,
      description,
      check.foldF(
        { case TopologyResult(beforeEstablishedBaseResult, mapping) =>
          val updatedMapping = update(mapping)
          proposeMapping(
            store,
            updatedMapping,
            signedBy,
            serial = beforeEstablishedBaseResult.serial + PositiveInt.one,
            isProposal = isProposal,
          )
            .flatMap { _ =>
              retryProvider.retry(
                retryFor,
                s"check established $description",
                check
                  .leftFlatMap[TopologyResult[M], RuntimeException] { currentAuthorizedState =>
                    if (currentAuthorizedState.base.serial == beforeEstablishedBaseResult.serial) {
                      check
                        .leftMap(_ =>
                          Status.FAILED_PRECONDITION
                            .withDescription("Condition is not yet observed.")
                            .asRuntimeException()
                        )
                    } else {
                      EitherT.leftT(
                        AuthorizedStateChanged(
                          currentAuthorizedState.base.serial
                        )
                      )
                    }
                  }
                  .rethrowT,
                logger,
              )
            }
            .recover {
              case ex: AuthorizedStateChanged if recreateOnAuthorizedStateChange =>
                logger.info(
                  s"check $description failed and the base state has changed, re-establishing condition"
                )
                throw Status.FAILED_PRECONDITION.withDescription(ex.getMessage).asRuntimeException()
            }
        },
        Future.successful,
      ),
      logger,
    )

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
            ParticipantPermissionX.Submission,
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
        HostingParticipant(newParticipant, ParticipantPermissionX.Observation)
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
                  && proposal.mapping.threshold == CNThresholds.partyToParticipantThreshold(
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
            threshold = CNThresholds.partyToParticipantThreshold(newHostingParticipants),
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
      val newValue = HostingParticipant(participantId, ParticipantPermissionX.Submission)
      val oldIndex = participants.indexWhere(_.participantId == newValue.participantId)
      participants.updated(oldIndex, newValue)
    }

    ensureTopologyMapping[PartyToParticipantX](
      TopologyStoreId.DomainStore(domainId),
      s"Participant $participantId is promoted to have Submission permission for party $party",
      EitherT(getPartyToParticipant(domainId, party).map(result => {
        Either.cond(
          result.mapping.participants
            .contains(HostingParticipant(participantId, ParticipantPermissionX.Submission)),
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
              threshold = CNThresholds.partyToParticipantThreshold(newHostingParticipants),
            )
          },
          show"Participant $participantId does not host party $party",
        )
      },
      retryFor,
      signedBy,
    )
  }

  def addTopologyTransactionsAndEnsurePersisted(
      domainId: Option[DomainId],
      txs: Seq[GenericSignedTopologyTransactionX],
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    logger.info(
      s"Adding topology transactions $txs"
    )
    retryProvider
      .ensureThat[Seq[GenericSignedTopologyTransactionX], Seq[
        GenericSignedTopologyTransactionX
      ], Seq[Status.Code]](
        RetryFor.ClientCalls,
        "Topology transaction are added",
        listAllTransactions(
          domainId.map(TopologyStoreId.DomainStore(_)),
          TimeQueryX.Range(None, None),
        ).map { stored =>
          val signedStoredTransactions = stored.map(_.transaction.transaction)
          // filter just based on the transaction, ignoring signature differences
          val missingTxs =
            txs.filterNot(transaction => signedStoredTransactions.contains(transaction.transaction))
          if (missingTxs.nonEmpty) {
            logger.debug(s"Found missing transactions $missingTxs")
          }
          Either.cond(missingTxs.isEmpty, txs, missingTxs)
        },
        missingTx => {
          logger.debug(s"Submitting missing transactions $missingTx")
          addTopologyTransactions(domainId, missingTx)
        },
        logger,
      )
      .map(_.discard)
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
        getSequencerDomainState(domainId).map(result =>
          Either
            .cond(
              result.mapping.active.forgetNE == sequencerChange(result.mapping.active),
              result,
              result,
            )
        )
      ),
      previous => {
        val newSequencers = sequencerChange(previous.active)
        logger.debug(
          s"Applying sequencer change: previous [${previous.active}], wanted [$newSequencers]"
        )
        // The threshold in here does not matter for anything at this point.
        // It is purely on the read side through the sequencer trust threshold.
        SequencerDomainStateX.create(
          previous.domain,
          CNThresholds
            .sequencerConnectionsSizeThreshold(newSequencers.size),
          newSequencers,
          previous.observers,
        )
      },
      retryFor,
      signedBy,
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
          CNThresholds
            .mediatorDomainStateThreshold(newMediators.size),
          newMediators,
          previous.observers,
        )
      },
      retryFor,
      signedBy,
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
          CNThresholds.decentralizedNamespaceThreshold(
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

  def ensureTrafficControlState(
      domainId: DomainId,
      member: Member,
      newTotalExtraTrafficLimit: Long,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.ensureThat(
      RetryFor.WaitingOnInitDependency,
      s"Extra traffic limit for $member on domain $domainId set to $newTotalExtraTrafficLimit (or higher)",
      lookupTrafficControlState(domainId, member).map(result =>
        Either.cond(
          result.fold(0L)(_.mapping.totalExtraTrafficLimit.value) >= newTotalExtraTrafficLimit,
          (),
          result,
        )
      ),
      (previousOrNone: Option[TopologyResult[TrafficControlStateX]]) =>
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
    runCmd(
      TopologyAdminCommandsX.Read.DomainParametersState(
        BaseQueryX(
          domainId.filterString,
          proposals = proposals.proposals,
          TimeQueryX.HeadState,
          None,
          filterSigningKey = proposals.signingKey.getOrElse(""),
          protocolVersion = None,
        ),
        domainId.filterString,
      )
    ).map(_.map(r => TopologyResult(r.context, DomainParametersStateX(domainId, r.item))))
      .map(_.headOption.getOrElse {
        throw Status.NOT_FOUND
          .withDescription(s"No DomainParametersState state domain $domainId")
          .asRuntimeException
      })
  }

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

  def listMyKeys()(implicit
      traceContext: TraceContext
  ): Future[Seq[com.digitalasset.canton.crypto.admin.grpc.PrivateKeyMetadata]] = {
    runCmd(VaultAdminCommands.ListMyKeys("", ""))
  }

  def exportKeyPair(fingerprint: Fingerprint)(implicit
      traceContext: TraceContext
  ): Future[ByteString] = {
    runCmd(VaultAdminCommands.ExportKeyPair(fingerprint, ProtocolVersion.latest))
  }

  def importKeyPair(keyPair: Array[Byte], name: Option[String])(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(VaultAdminCommands.ImportKeyPair(ByteString.copyFrom(keyPair), name))
  }

}

object TopologyAdminConnection {
  private val TOPOLOGY_CHANGE_SKEW: Duration = Duration.ofMillis(100)

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
