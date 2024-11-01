// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.{EitherT, OptionT}
import cats.syntax.either.*
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.RetryProvider.QuietNonRetryableException
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.{
  AuthorizedStateChanged,
  TopologyTransactionType,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommands.Init.GetIdResult
import com.digitalasset.canton.admin.api.client.commands.{
  DomainTimeCommands,
  TopologyAdminCommands,
  VaultAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.topology.{
  BaseResult,
  ListNamespaceDelegationResult,
  ListOwnerToKeyMappingResult,
  ListVettedPackagesResult,
}
import com.digitalasset.canton.config.{
  ApiLoggingConfig,
  ClientConfig,
  NonNegativeDuration,
  NonNegativeFiniteDuration,
}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.crypto.{
  EncryptionPublicKey,
  Fingerprint,
  PublicKey,
  SigningKeyUsage,
  SigningPublicKey,
}
import com.digitalasset.canton.crypto.SigningKeySpec.EcCurve25519
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.pretty.PrettyUtil.*
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.time.FetchTimeResponse
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.admin.grpc
import com.digitalasset.canton.topology.admin.grpc.BaseQuery
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransaction,
  TimeQuery,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.store.TimeQuery.HeadState
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}
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
  import TopologyAdminConnection.{RecreateOnAuthorizedStateChange, TopologyResult}

  override val serviceName = "Canton Participant Admin API"

  private val memberIdO: AtomicReference[Option[GetIdResult]] = new AtomicReference(None)

  def getId(
  )(implicit traceContext: TraceContext): Future[UniqueIdentifier] =
    getIdOption().map {
      case GetIdResult(true, Some(uniqueIdentifier)) => uniqueIdentifier
      case GetIdResult(_, _) =>
        throw Status.UNAVAILABLE
          .withDescription(
            s"Node does not have an Id assigned yet."
          )
          .asRuntimeException()
    }

  def getIdOption(
  )(implicit traceContext: TraceContext): Future[GetIdResult] =
    // We don't lock around this, worst case we make concurrent requests
    // that both update memberIdO which is fine as the request is idempotent.
    memberIdO.get() match {
      case Some(memberId) => Future.successful(memberId)
      case None =>
        for {
          memberId <- runCmd(
            TopologyAdminCommands.Init.GetId()
          )
        } yield {
          if (memberId.initialized) {
            memberIdO.set(Some(memberId))
          }
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
      timeout: NonNegativeDuration = retryProvider.timeouts.default,
  )(implicit traceContext: TraceContext): Future[FetchTimeResponse] =
    runCmd(
      DomainTimeCommands.FetchTime(
        Some(domainId),
        freshnessBound =
          com.digitalasset.canton.time.NonNegativeFiniteDuration.fromConfig(maxDomainTimeLag),
        timeout = timeout,
      )
    )

  def listPartyToParticipant(
      filterStore: String = "",
      operation: Option[TopologyChangeOp] = None,
      filterParty: String = "",
      filterParticipant: String = "",
      timeQuery: TimeQuery = TimeQuery.HeadState,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipant]]] = {
    runCmd(
      TopologyAdminCommands.Read.ListPartyToParticipant(
        BaseQuery(
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

  def listPartyToKey(
      operation: Option[TopologyChangeOp] = None,
      filterStore: TopologyStoreId,
      filterParty: Option[PartyId] = None,
      timeQuery: TimeQuery = TimeQuery.HeadState,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToKeyMapping]]] = {
    runCmd(
      TopologyAdminCommands.Read.ListPartyToKeyMapping(
        BaseQuery(
          filterStore = filterStore.filterName,
          proposals = proposals.proposals,
          timeQuery,
          operation,
          filterSigningKey = proposals.signingKey.getOrElse(""),
          protocolVersion = None,
        ),
        filterParty.fold("")(_.toProtoPrimitive),
      )
    ).map(_.map(r => TopologyResult(r.context, r.item)))
  }

  private def findPartyToParticipant(
      domainId: DomainId,
      partyId: PartyId,
  )(implicit traceContext: TraceContext): OptionT[Future, TopologyResult[PartyToParticipant]] =
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
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] =
    findPartyToParticipant(domainId, partyId).getOrElse {
      throw Status.NOT_FOUND
        .withDescription(s"No PartyToParticipant state for $partyId on domain $domainId")
        .asRuntimeException
    }

  def listSequencerDomainState(
      domainId: DomainId,
      timeQuery: TimeQuery,
      proposals: Boolean = false,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[SequencerDomainState]]] =
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
  ): Future[Seq[TopologyResult[SequencerDomainState]]] =
    runCmd(
      TopologyAdminCommands.Read.SequencerDomainState(
        BaseQuery(
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
  ): Future[TopologyResult[SequencerDomainState]] =
    listSequencerDomainState(domainId, HeadState, proposals).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No sequencer state for domain $domainId")
            .asRuntimeException()
        )
    }

  def getMediatorDomainState(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorDomainState]] =
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
  ): Future[Seq[TopologyResult[MediatorDomainState]]] = {
    runCmd(
      TopologyAdminCommands.Read.MediatorDomainState(
        BaseQuery(
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
  ): Future[TopologyResult[DecentralizedNamespaceDefinition]] =
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
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[DecentralizedNamespaceDefinition]]] = {
    runCmd(
      TopologyAdminCommands.Read.ListDecentralizedNamespaceDefinition(
        BaseQuery(
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
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransaction]] =
    listAllTransactions(
      store,
      includeMappings = Set(
        TopologyMapping.Code.NamespaceDelegation,
        TopologyMapping.Code.OwnerToKeyMapping,
        TopologyMapping.Code.IdentifierDelegation,
      ),
      filterNamespace = Some(id.namespace),
    ).map(_.map(_.transaction))

  def listAllTransactions(
      store: TopologyStoreId,
      timeQuery: TimeQuery = TimeQuery.HeadState,
      proposals: Boolean = false,
      includeMappings: Set[TopologyMapping.Code] = Set.empty,
      filterNamespace: Option[Namespace] = None,
  )(implicit
      tc: TraceContext
  ): Future[Seq[StoredTopologyTransaction[TopologyChangeOp, TopologyMapping]]] = {
    runCmd(
      TopologyAdminCommands.Read.ListAll(
        query = BaseQuery(
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
          else TopologyMapping.Code.all.diff(includeMappings.toSeq).map(_.code),
      )
    ).map(_.result)
  }

  def ensureInitialOwnerToKeyMapping(
      member: Member,
      keys: NonEmpty[Seq[PublicKey]],
      signedBy: Seq[Fingerprint],
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
      signedBy: Seq[Fingerprint],
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, OwnerToKeyMapping]] =
    proposeMapping(
      TopologyStoreId.AuthorizedStore,
      OwnerToKeyMapping(
        member,
        keys = keys,
      ),
      signedBy = signedBy,
      serial = PositiveInt.one,
      isProposal = false,
      change = TopologyChangeOp.Replace,
      forceChanges = ForceFlags.none,
    )

  private def listOwnerToKeyMapping(member: Member)(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[OwnerToKeyMapping]]] =
    runCmd(
      TopologyAdminCommands.Read.ListOwnerToKeyMapping(
        BaseQuery(
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
      excludeMappings: Seq[TopologyMapping.Code],
      filterNamespace: String,
  )(implicit
      traceContext: TraceContext
  ): Future[ByteString] = {
    runCmd(
      TopologyAdminCommands.Read.ExportTopologySnapshot(
        BaseQuery(
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
      TopologyAdminCommands.Write.ImportTopologySnapshot(topologyTransactions, store.filterName)
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
      excludeMappings = TopologyMapping.Code.all.diff(
        Seq(
          TopologyMapping.Code.NamespaceDelegation,
          TopologyMapping.Code.OwnerToKeyMapping,
          TopologyMapping.Code.IdentifierDelegation,
          // only relevant for participants
          TopologyMapping.Code.VettedPackages,
        )
      ),
      filterNamespace = participantId.namespace.filterString,
    )

  def listVettedPackages(
      participantId: ParticipantId,
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[Seq[ListVettedPackagesResult]] = {
    runCmd(
      TopologyAdminCommands.Read.ListVettedPackages(
        BaseQuery(
          filterStore = TopologyStoreId.DomainStore(domainId).filterName,
          proposals = false,
          timeQuery = TimeQuery.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParticipant = participantId.filterString,
      )
    )
  }

  def proposeMapping[M <: TopologyMapping: ClassTag](
      store: TopologyStoreId,
      mapping: M,
      signedBy: Fingerprint,
      serial: PositiveInt,
      isProposal: Boolean,
      change: TopologyChangeOp = TopologyChangeOp.Replace,
      forceChanges: ForceFlags = ForceFlags.none,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransaction[TopologyChangeOp, M]] =
    proposeMapping(
      store,
      mapping,
      Seq(signedBy),
      serial,
      isProposal,
      change,
      forceChanges,
    )

  def proposeMapping[M <: TopologyMapping: ClassTag](
      store: TopologyStoreId,
      mapping: M,
      signedBy: Seq[Fingerprint],
      serial: PositiveInt,
      isProposal: Boolean,
      change: TopologyChangeOp,
      forceChanges: ForceFlags,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransaction[TopologyChangeOp, M]] =
    runCmd(
      TopologyAdminCommands.Write.Propose(
        mapping = mapping,
        signedBy = signedBy,
        store = store.filterName,
        serial = Some(serial),
        mustFullyAuthorize = !isProposal,
        change = change,
        forceChanges = forceChanges,
      )
    )

  private def proposeMapping[M <: TopologyMapping: ClassTag](
      store: TopologyStoreId,
      mapping: Either[String, M],
      signedBy: Fingerprint,
      serial: PositiveInt,
      isProposal: Boolean,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransaction[TopologyChangeOp, M]] =
    proposeMapping(
      store,
      mapping.valueOr(err => throw new IllegalArgumentException(s"Invalid topology mapping: $err")),
      signedBy,
      serial,
      isProposal,
    )

  private def proposeMapping[M <: TopologyMapping: ClassTag](
      store: TopologyStoreId,
      mapping: Either[String, M],
      signedBy: Fingerprint,
      serial: PositiveInt,
      isProposal: Boolean,
      forceChanges: ForceFlags,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransaction[TopologyChangeOp, M]] =
    proposeMapping(
      store,
      mapping.valueOr(err => throw new IllegalArgumentException(s"Invalid topology mapping: $err")),
      signedBy,
      serial,
      isProposal,
      forceChanges = forceChanges,
    )

  /** Prepare a transaction for external signing.
    */
  def generateTransactions(
      proposals: Seq[TopologyAdminCommands.Write.GenerateTransactions.Proposal]
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyTransaction[TopologyChangeOp, TopologyMapping]]] =
    runCmd(
      TopologyAdminCommands.Write.GenerateTransactions(
        proposals
      )
    )

  /** Version of [[ensureTopologyMapping]] that also handles proposals:
    * - a new topology transaction is created as a proposal
    * - checks the proposals as well to see if the check holds
    */
  private def ensureTopologyProposal[M <: TopologyMapping: ClassTag](
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
  def ensureTopologyMapping[M <: TopologyMapping: ClassTag](
      store: TopologyStoreId,
      description: String,
      check: => EitherT[Future, TopologyResult[M], TopologyResult[M]],
      update: M => Either[String, M],
      retryFor: RetryFor,
      signedBy: Fingerprint,
      isProposal: Boolean = false,
      recreateOnAuthorizedStateChange: RecreateOnAuthorizedStateChange =
        RecreateOnAuthorizedStateChange.Recreate,
      forceChanges: ForceFlags = ForceFlags.none,
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
                    forceChanges = forceChanges,
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
  ): Future[SignedTopologyTransaction[TopologyChangeOp, PartyToParticipant]] = {
    proposeInitialPartyToParticipant(store, partyId, Seq(participantId), signedBy)
  }
  def proposeInitialPartyToParticipant(
      store: TopologyStoreId,
      partyId: PartyId,
      participants: Seq[ParticipantId],
      signedBy: Fingerprint,
      isProposal: Boolean = false,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, PartyToParticipant]] = {
    val hostingParticipants = participants.map(
      HostingParticipant(
        _,
        ParticipantPermission.Submission,
      )
    )
    proposeMapping(
      store,
      PartyToParticipant.tryCreate(
        partyId,
        Thresholds.partyToParticipantThreshold(hostingParticipants),
        hostingParticipants,
      ),
      signedBy = signedBy,
      serial = PositiveInt.one,
      isProposal = isProposal,
    )
  }

  def ensurePartyToParticipantRemovalProposal(
      domainId: DomainId,
      party: PartyId,
      participantToRemove: ParticipantId,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[PartyToParticipant]] = {
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
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
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
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    ensureTopologyMapping[PartyToParticipant](
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
          PartyToParticipant.tryCreate(
            previous.partyId,
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
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
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

    ensureTopologyProposal[PartyToParticipant](
      TopologyStoreId.DomainStore(domainId),
      description,
      queryType => findPartyToParticipant(queryType),
      previous => {
        val newHostingParticipants = participantChange(previous.participants)
        Right(
          PartyToParticipant.tryCreate(
            previous.partyId,
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
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    def promoteParticipantToSubmitter(
        participants: Seq[HostingParticipant]
    ): Seq[HostingParticipant] = {
      val newValue = HostingParticipant(participantId, ParticipantPermission.Submission)
      val oldIndex = participants.indexWhere(_.participantId == newValue.participantId)
      participants.updated(oldIndex, newValue)
    }

    ensureTopologyMapping[PartyToParticipant](
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
            PartyToParticipant.tryCreate(
              previous.partyId,
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
      txs: Seq[GenericSignedTopologyTransaction],
      flags: ForceFlag*
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      TopologyAdminCommands.Write
        .AddTransactions(
          txs,
          store.filterName,
          ForceFlags(
            flags*
          ),
        )
    )

  def proposeInitialSequencerDomainState(
      domainId: DomainId,
      active: Seq[SequencerId],
      observers: Seq[SequencerId],
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, SequencerDomainState]] =
    proposeMapping(
      TopologyStoreId.AuthorizedStore,
      SequencerDomainState.create(
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
  ): Future[TopologyResult[SequencerDomainState]] = {
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
  ): Future[TopologyResult[SequencerDomainState]] = {
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
  ): Future[TopologyResult[SequencerDomainState]] = {
    ensureTopologyMapping[SequencerDomainState](
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
        SequencerDomainState.create(
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
  ): Future[SignedTopologyTransaction[TopologyChangeOp, MediatorDomainState]] =
    proposeMapping(
      TopologyStoreId.AuthorizedStore,
      MediatorDomainState.create(
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
  ): Future[TopologyResult[MediatorDomainState]] = {
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
  ): Future[TopologyResult[MediatorDomainState]] = {
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
  ): Future[TopologyResult[MediatorDomainState]] =
    ensureTopologyMapping[MediatorDomainState](
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
        MediatorDomainState.create(
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
  ): Future[SignedTopologyTransaction[TopologyChangeOp, DecentralizedNamespaceDefinition]] =
    proposeMapping(
      TopologyStoreId.AuthorizedStore,
      DecentralizedNamespaceDefinition.create(
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
  ): Future[TopologyResult[DecentralizedNamespaceDefinition]] =
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
  ): Future[TopologyResult[DecentralizedNamespaceDefinition]] =
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
  ): Future[TopologyResult[DecentralizedNamespaceDefinition]] =
    ensureTopologyMapping[DecentralizedNamespaceDefinition](
      TopologyStoreId.DomainStore(domainId),
      description,
      decentralizedNamespaceDefinitionForNamespace(domainId, decentralizedNamespace, ownerChange),
      previous => {
        // constructor is not exposed so no copy
        val newOwners = ownerChange(previous.owners)
        DecentralizedNamespaceDefinition.create(
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
  ): Future[SignedTopologyTransaction[TopologyChangeOp, DomainParametersState]] =
    proposeMapping(
      TopologyStoreId.AuthorizedStore,
      DomainParametersState(domainId, parameters),
      signedBy = signedBy,
      serial = PositiveInt.one,
      isProposal = false,
    )

  def ensureDomainParameters(
      domainId: DomainId,
      parametersBuilder: DynamicDomainParameters => DynamicDomainParameters,
      signedBy: Fingerprint,
      forceChanges: ForceFlags = ForceFlags.none,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DomainParametersState]] =
    ensureTopologyMapping[DomainParametersState](
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
      forceChanges = forceChanges,
    )

  def getDomainParametersState(
      domainId: DomainId,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit tc: TraceContext): Future[TopologyResult[DomainParametersState]] = {
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
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[DomainParametersState]]] = {
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
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[DomainParametersState]]] = {
    runCmd(
      TopologyAdminCommands.Read.DomainParametersState(
        BaseQuery(
          storeId.filterName,
          proposals = proposals.proposals,
          timeQuery,
          None,
          filterSigningKey = proposals.signingKey.getOrElse(""),
          protocolVersion = None,
        ),
        domainId.filterString,
      )
    ).map(_.map(r => TopologyResult(r.context, DomainParametersState(domainId, r.item))))
  }

  private def listNamespaceDelegation(namespace: Namespace, target: Option[SigningPublicKey])(
      implicit traceContext: TraceContext
  ): Future[Seq[TopologyResult[NamespaceDelegation]]] =
    runCmd(
      TopologyAdminCommands.Read.ListNamespaceDelegation(
        BaseQuery(
          filterStore = AuthorizedStore.filterName,
          proposals = false,
          timeQuery = TimeQuery.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterNamespace = namespace.unwrap,
        filterTargetKey = target.map(_.fingerprint),
      )
    ).map { txs =>
      txs.map { case ListNamespaceDelegationResult(context, item) =>
        TopologyResult(context, item)
      }
    }

  def ensureNamespaceDelegation(
      namespace: Namespace,
      target: SigningPublicKey,
      isRootDelegation: Boolean,
      signedBy: Fingerprint,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.ensureThatB(
      retryFor,
      "ensure_namespace_delegation",
      show"Namespace delegation for $namespace exists",
      listNamespaceDelegation(
        namespace,
        Some(target),
      ).map(_.nonEmpty),
      proposeNamespaceDelegation(namespace, target, isRootDelegation, signedBy).map(_ => ()),
      logger,
    )

  def proposeNamespaceDelegation(
      namespace: Namespace,
      target: SigningPublicKey,
      isRootDelegation: Boolean,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, NamespaceDelegation]] =
    proposeMapping(
      TopologyStoreId.AuthorizedStore,
      NamespaceDelegation.create(
        namespace,
        target,
        isRootDelegation,
      ),
      signedBy = signedBy,
      serial = PositiveInt.one,
      isProposal = false,
    )

  def initId(id: NodeIdentity)(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(TopologyAdminCommands.Init.InitId(id.uid.toProtoPrimitive))
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

  def generateKeyPair(name: String, usage: NonEmpty[Set[SigningKeyUsage]])(implicit
      traceContext: TraceContext
  ): Future[SigningPublicKey] = {
    runCmd(VaultAdminCommands.GenerateSigningKey(name, usage, Some(EcCurve25519)))
  }

  def generateEncryptionKeyPair(name: String)(implicit
      traceContext: TraceContext
  ): Future[EncryptionPublicKey] = {
    runCmd(VaultAdminCommands.GenerateEncryptionKey(name, None))
  }

  def importKeyPair(keyPair: Array[Byte], name: Option[String])(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(VaultAdminCommands.ImportKeyPair(ByteString.copyFrom(keyPair), name, password = None))
  }

  def listDomainTrustCertificate(domainId: DomainId, member: Member)(implicit
      tc: TraceContext
  ): Future[Seq[TopologyResult[DomainTrustCertificate]]] =
    runCmd(
      TopologyAdminCommands.Read.ListDomainTrustCertificate(
        BaseQuery(
          TopologyStoreId.DomainStore(domainId).filterName,
          proposals = false,
          timeQuery = TimeQuery.HeadState,
          ops = Some(TopologyChangeOp.Replace),
          filterSigningKey = "",
          protocolVersion = None,
        ),
        member.filterString,
      )
    ).map(
      // TODO(#14815) Canton currently compares member IDs by string prefix instead of strict equality of
      // member IDs in ListDomainTrustCertificate, so we apply another filter for equality of the member ID
      _.filter(r => r.item.participantId.member.filterString == member.filterString)
        .map(r =>
          TopologyResult(
            r.context,
            r.item,
          )
        )
    )

  def ensureDomainTrustCertificateRemoved(
      retryFor: RetryFor,
      domainId: DomainId,
      member: Member,
      signedBy: Fingerprint,
  )(implicit tc: TraceContext): Future[Unit] =
    retryProvider.ensureThat(
      retryFor,
      "ensure_domain_trust_certificate_removed",
      s"Remove domain trust certificate for $member on $domainId",
      listDomainTrustCertificate(domainId, member).map {
        case Seq() => Right(())
        case Seq(cert) => Left(cert)
        case certs =>
          throw Status.INTERNAL
            .withDescription(
              s"Expected at most one DomainTrustCertificate for $member but got: $certs"
            )
            .asRuntimeException()
      },
      (previous: TopologyResult[DomainTrustCertificate]) =>
        proposeMapping(
          TopologyStoreId.DomainStore(domainId),
          previous.mapping,
          signedBy,
          previous.base.serial + PositiveInt.one,
          isProposal = false,
          change = TopologyChangeOp.Remove,
        ).map(_ => ()),
      logger,
    )

  def ensurePartyToParticipantRemoved(
      retryFor: RetryFor,
      domainId: DomainId,
      partyId: PartyId,
      signedBy: Fingerprint,
  )(implicit tc: TraceContext): Future[Unit] =
    retryProvider.ensureThat(
      retryFor,
      "ensure_party_to_participant_removed",
      s"Remove party to participant for $partyId on $domainId",
      listPartyToParticipant(
        TopologyStoreId.DomainStore(domainId).filterName,
        Some(TopologyChangeOp.Replace),
        filterParty = partyId.filterString,
      ).map {
        case Seq() => Right(())
        case Seq(mapping) => Left(mapping)
        case mappings =>
          throw Status.INTERNAL
            .withDescription(
              s"Expected at most one PartyToParticipant mapping for $partyId but got: $mappings"
            )
            .asRuntimeException()
      },
      (previous: TopologyResult[PartyToParticipant]) =>
        proposeMapping(
          TopologyStoreId.DomainStore(domainId),
          previous.mapping,
          signedBy,
          previous.base.serial + PositiveInt.one,
          isProposal = false,
          change = TopologyChangeOp.Remove,
        ).map(_ => ()),
      logger,
    )

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

  final case class TopologyResult[M <: TopologyMapping](
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
