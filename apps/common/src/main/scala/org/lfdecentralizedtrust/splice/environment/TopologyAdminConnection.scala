// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.{EitherT, OptionT}
import cats.implicits.catsSyntaxOptionId
import cats.syntax.applicative.*
import cats.syntax.either.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommands.Init.GetIdResult
import com.digitalasset.canton.admin.api.client.commands.{
  GrpcAdminCommand,
  SynchronizerTimeCommands,
  TopologyAdminCommands,
  VaultAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.topology
import com.digitalasset.canton.admin.api.client.data.topology.{
  BaseResult,
  ListOwnerToKeyMappingResult,
  ListNamespaceDelegationResult,
  ListSynchronizerParametersStateResult,
}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.config.{
  ApiLoggingConfig,
  ClientConfig,
  NonNegativeDuration,
  NonNegativeFiniteDuration,
}
import com.digitalasset.canton.crypto.{
  EncryptionPublicKey,
  Fingerprint,
  PublicKey,
  SigningKeyUsage,
  SigningPublicKey,
}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.grpc.ByteStringStreamObserver
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.PrettyUtil.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
import com.digitalasset.canton.time.{Clock, FetchTimeResponse}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.admin.grpc
import com.digitalasset.canton.topology.admin.grpc.{BaseQuery, TopologyStoreId}
import com.digitalasset.canton.topology.admin.v30.ExportTopologySnapshotResponse
import com.digitalasset.canton.topology.store.TimeQuery.HeadState
import com.digitalasset.canton.topology.store.{StoredTopologyTransaction, TimeQuery}
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.RetryProvider.QuietNonRetryableException
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.{
  AuthorizedState,
  ProposalSignedByOwnKey,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.{
  AuthorizedStateChanged,
  TopologyTransactionType,
}

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
      synchronizerId: SynchronizerId,
      maxDomainTimeLag: NonNegativeFiniteDuration,
      timeout: NonNegativeDuration = retryProvider.timeouts.default,
  )(implicit traceContext: TraceContext): Future[FetchTimeResponse] =
    runCmd(
      SynchronizerTimeCommands.FetchTime(
        // TODO(#456) Use the proper serial and protocol version
        Some(PhysicalSynchronizerId(synchronizerId, ProtocolVersion.v34, NonNegativeInt.zero)),
        freshnessBound =
          com.digitalasset.canton.time.NonNegativeFiniteDuration.fromConfig(maxDomainTimeLag),
        timeout = timeout,
      )
    )

  def listPartyToParticipantFromAllStores(
      filterParty: String
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipant]]] = {
    runCmd(
      TopologyAdminCommands.Read.ListPartyToParticipant(
        BaseQuery(
          store = None,
          proposals = false,
          timeQuery = TimeQuery.HeadState,
          ops = Some(TopologyChangeOp.Replace),
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParty,
        "",
      )
    ).map(_.map(result => TopologyResult(result.context, result.item)))
  }

  def listPartyToParticipant(
      store: Option[TopologyStoreId] = None,
      // list only active (non-removed) mappings by default; this matches the Canton console defaults
      operation: Option[TopologyChangeOp] = Some(TopologyChangeOp.Replace),
      filterParty: String = "",
      filterParticipant: String = "",
      timeQuery: TimeQuery = TimeQuery.HeadState,
      topologyTransactionType: TopologyTransactionType = AuthorizedState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipant]]] = {
    runCommand(
      store.getOrElse(
        TopologyStoreId.Authorized
      ),
      topologyTransactionType,
      timeQuery,
      operation,
    )(
      TopologyAdminCommands.Read.ListPartyToParticipant(
        _,
        filterParty,
        filterParticipant,
      )
    )
  }

  def listPartyToKey(
      operation: Option[TopologyChangeOp] = None,
      filterStore: TopologyStoreId,
      filterParty: Option[PartyId] = None,
      timeQuery: TimeQuery = TimeQuery.HeadState,
      topologyTransactionType: TopologyTransactionType = AuthorizedState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToKeyMapping]]] = {
    runCommand(
      filterStore,
      topologyTransactionType,
      timeQuery,
      operation,
    ) { baseQuery =>
      TopologyAdminCommands.Read.ListPartyToKeyMapping(
        baseQuery,
        filterParty.fold("")(_.toProtoPrimitive),
      )
    }
  }

  private def findPartyToParticipant(
      synchronizerId: SynchronizerId,
      partyId: PartyId,
      operation: Option[TopologyChangeOp],
      topologyTransactionType: TopologyTransactionType,
  )(implicit traceContext: TraceContext): OptionT[Future, TopologyResult[PartyToParticipant]] =
    OptionT(
      listPartyToParticipant(
        store = Some(TopologyStoreId.Synchronizer(synchronizerId)),
        filterParty = partyId.filterString,
        operation = operation,
        topologyTransactionType = topologyTransactionType,
      ).map { txs =>
        txs.headOption
      }
    )

  def getPartyToParticipant(
      synchronizerId: SynchronizerId,
      partyId: PartyId,
      // get only active (non-removed) mappings by default; this matches the Canton console defaults
      operation: Option[TopologyChangeOp] = Some(TopologyChangeOp.Replace),
      topologyTransactionType: TopologyTransactionType = AuthorizedState,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] =
    findPartyToParticipant(synchronizerId, partyId, operation, topologyTransactionType).getOrElse {
      throw Status.NOT_FOUND
        .withDescription(s"No PartyToParticipant state for $partyId on domain $synchronizerId")
        .asRuntimeException
    }

  def listSequencerSynchronizerState(
      synchronizerId: SynchronizerId,
      timeQuery: TimeQuery,
      topologyTransactionType: TopologyTransactionType,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[SequencerSynchronizerState]]] =
    listSequencerSynchronizerState(
      TopologyStoreId.Synchronizer(synchronizerId),
      synchronizerId,
      timeQuery,
      topologyTransactionType,
    )

  def listSequencerSynchronizerState(
      store: TopologyStoreId,
      synchronizerId: SynchronizerId,
      timeQuery: TimeQuery,
      topologyTransactionType: TopologyTransactionType,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[SequencerSynchronizerState]]] =
    runCommand(
      store,
      topologyTransactionType,
      timeQuery,
    )(baseQuery =>
      TopologyAdminCommands.Read.ListSequencerSynchronizerState(
        baseQuery,
        filterSynchronizerId = synchronizerId.filterString,
      )
    )

  def getSequencerSynchronizerState(
      synchronizerId: SynchronizerId,
      topologyTransactionType: TopologyTransactionType = AuthorizedState,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerSynchronizerState]] =
    listSequencerSynchronizerState(synchronizerId, HeadState, topologyTransactionType).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No sequencer state for domain $synchronizerId")
            .asRuntimeException()
        )
    }

  def getMediatorSynchronizerState(
      synchronizerId: SynchronizerId,
      topologyTransactionType: TopologyTransactionType = AuthorizedState,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorSynchronizerState]] =
    listMediatorSynchronizerState(
      TopologyStoreId.Synchronizer(synchronizerId),
      synchronizerId,
      topologyTransactionType,
    ).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No mediator state for domain $synchronizerId")
            .asRuntimeException()
        )
    }

  private def listMediatorSynchronizerState(
      store: TopologyStoreId,
      synchronizerId: SynchronizerId,
      topologyTransactionType: TopologyTransactionType,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[MediatorSynchronizerState]]] = {
    runCommand(
      store,
      topologyTransactionType,
    )(baseQuery =>
      TopologyAdminCommands.Read.ListMediatorSynchronizerState(
        baseQuery,
        filterSynchronizerId = synchronizerId.filterString,
      )
    )
  }

  def getDecentralizedNamespaceDefinition(
      synchronizerId: SynchronizerId,
      decentralizedNamespace: Namespace,
      topologyTransactionType: TopologyTransactionType = AuthorizedState,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DecentralizedNamespaceDefinition]] =
    listDecentralizedNamespaceDefinition(
      synchronizerId,
      decentralizedNamespace,
      topologyTransactionType,
    ).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(
              show"No decentralized namespace definition for $decentralizedNamespace on domain $synchronizerId"
            )
            .asRuntimeException()
        )
    }

  private def listDecentralizedNamespaceDefinition(
      synchronizerId: SynchronizerId,
      decentralizedNamespace: Namespace,
      topologyTransactionType: TopologyTransactionType,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[DecentralizedNamespaceDefinition]]] = {
    runCommand(
      TopologyStoreId.Synchronizer(synchronizerId),
      topologyTransactionType,
    )(baseQuery =>
      TopologyAdminCommands.Read.ListDecentralizedNamespaceDefinition(
        baseQuery,
        filterNamespace = decentralizedNamespace.toProtoPrimitive,
      )
    )
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
          store = store,
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

  // only creates a mapping if no mapping exists, so you might not get those keys
  def ensureInitialOwnerToKeyMapping(
      member: Member,
      keys: NonEmpty[Seq[PublicKey]],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.ensureThatB(
      retryFor,
      "ensure_initial_owner_to_key_mapping",
      show"An initial key mapping for $member exists",
      listOwnerToKeyMapping(
        member
      ).map(_.nonEmpty),
      proposeOwnerToKeyMapping(member, keys, PositiveInt.one).map(_ => ()),
      logger,
    )

  // overwrites existing mappings, so you definitely end up with those keys
  def ensureOwnerToKeyMapping(
      member: Member,
      keys: NonEmpty[Seq[PublicKey]],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    // We make two transactions - one to add new keys one to remove old keys - to ensure overlap and a smooth transition.
    // S.a. Canton's `rotate_key`
    for {
      _ <- retryProvider.ensureThat(
        retryFor,
        "ensure_owner_to_key_mapping_add",
        show"Key mapping for $member exists and contains the keys ${keys.map(_.fingerprint)}",
        for {
          ownerToKeyMappings <- listOwnerToKeyMapping(member)
        } yield ownerToKeyMappings match {
          case Seq() => Left(None)
          case Seq(mapping) =>
            Either.cond(
              keys.toSet.subsetOf(mapping.mapping.keys.toSet),
              (),
              Some(mapping),
            )
          case _ => throw new IllegalStateException("Multiple owner to key mappings found")
        },
        (mappingO: Option[TopologyResult[OwnerToKeyMapping]]) =>
          (mappingO match {
            case None => proposeOwnerToKeyMapping(member, keys, PositiveInt.one)
            case Some(mapping) =>
              proposeOwnerToKeyMapping(
                member,
                // Canton assumes the most recent keys that should be preferred are at the end
                // so we add keys at the end.
                // https://github.com/DACH-NY/canton/blob/1bd259d1364854f4bff5c21721cf351b4ed25cc6/community/base/src/main/scala/com/digitalasset/canton/crypto/CryptoKeys.scala#L228-L229
                mapping.mapping.keys ++ keys.filterNot(mapping.mapping.keys.contains(_)),
                mapping.base.serial + PositiveInt.one,
              )
          }).map(_ => ()),
        logger,
      )
      _ <- retryProvider.ensureThat(
        retryFor,
        "ensure_owner_to_key_mapping_trim",
        show"Key mapping for $member exists and contains exactly the keys ${keys.map(_.fingerprint)}",
        for {
          ownerToKeyMappings <- listOwnerToKeyMapping(member)
        } yield ownerToKeyMappings match {
          case Seq(mapping) =>
            Either.cond(
              keys.toSet == mapping.mapping.keys.toSet,
              (),
              mapping,
            )
          case _ =>
            throw new IllegalStateException("Unexpected number of owner to key mappings found")
        },
        (mapping: TopologyResult[OwnerToKeyMapping]) =>
          proposeOwnerToKeyMapping(member, keys, mapping.base.serial + PositiveInt.one)
            .map(_ => ()),
        logger,
      )
    } yield ()

  private def proposeOwnerToKeyMapping(
      member: Member,
      keys: NonEmpty[Seq[PublicKey]],
      serial: PositiveInt,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, OwnerToKeyMapping]] =
    proposeMapping(
      TopologyStoreId.Authorized,
      OwnerToKeyMapping(
        member,
        keys = keys,
      ),
      serial = serial,
      isProposal = false,
      change = TopologyChangeOp.Replace,
      forceChanges = ForceFlags.none,
    )

  def listOwnerToKeyMapping(member: Member, timeQuery: TimeQuery = TimeQuery.HeadState)(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[OwnerToKeyMapping]]] =
    runCmd(
      TopologyAdminCommands.Read.ListOwnerToKeyMapping(
        BaseQuery(
          store = TopologyStoreId.Authorized,
          proposals = false,
          timeQuery = timeQuery,
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
    val observer = new ByteStringStreamObserver[ExportTopologySnapshotResponse](_.chunk)
    runCmd(
      TopologyAdminCommands.Read.ExportTopologySnapshot(
        query = BaseQuery(
          store = store,
          proposals = proposals,
          timeQuery = TimeQuery.Range(from = None, until = None),
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterNamespace = filterNamespace,
        excludeMappings = excludeMappings.map(_.code),
        observer = observer,
      )
    ).discard
    observer.resultBytes
  }

  def importTopologySnapshot(
      topologyTransactions: ByteString,
      store: TopologyStoreId,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(
      TopologyAdminCommands.Write.ImportTopologySnapshot(
        topologyTransactions,
        store,
        waitToBecomeEffective = None,
      )
    )
  }

  def exportAuthorizedStoreSnapshot(
      participantId: UniqueIdentifier
  )(implicit
      tc: TraceContext
  ): Future[ByteString] =
    exportTopologySnapshot(
      TopologyStoreId.Authorized,
      proposals = false,
      excludeMappings = TopologyMapping.Code.all.diff(
        Seq(
          TopologyMapping.Code.NamespaceDelegation,
          TopologyMapping.Code.OwnerToKeyMapping,
        )
      ),
      filterNamespace = participantId.namespace.filterString,
    )

  def proposeMapping[M <: TopologyMapping: ClassTag](
      store: TopologyStoreId,
      mapping: M,
      serial: PositiveInt,
      isProposal: Boolean,
      change: TopologyChangeOp = TopologyChangeOp.Replace,
      forceChanges: ForceFlags = ForceFlags.none,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransaction[TopologyChangeOp, M]] =
    runCmd(
      TopologyAdminCommands.Write.Propose(
        mapping = mapping,
        // let canton figure out the signatures
        signedBy = Seq(),
        store = store,
        serial = Some(serial),
        mustFullyAuthorize = !isProposal,
        change = change,
        forceChanges = forceChanges,
        waitToBecomeEffective = None,
      )
    )

  protected def ensureInitialMapping[M <: TopologyMapping: ClassTag](
      mappingE: Either[String, M]
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransaction[TopologyChangeOp, M]] = {
    val mapping =
      mappingE.valueOr(err => throw new IllegalArgumentException(s"Invalid topology mapping: $err"))
    listAllTransactions(
      TopologyStoreId.Authorized,
      includeMappings = Set(
        mapping.code
      ),
    ).flatMap(sameCodeTopologyMappings =>
      sameCodeTopologyMappings
        .find(_.mapping.uniqueKey == mapping.uniqueKey)
        .flatMap(_.selectMapping[M])
        .fold({
          logger.info(
            s"Proposing initial mapping for ${mapping.code} with serial 1: $mapping"
          )
          proposeMapping(
            TopologyStoreId.Authorized,
            mapping,
            PositiveInt.one,
            isProposal = false,
          )
        }) { existingTxWithSameUniqueCode =>
          if (existingTxWithSameUniqueCode == mapping) {
            logger.info(
              s"Existing mapping found for ${mapping.code}: $mapping, returning existing transaction with serial ${existingTxWithSameUniqueCode.serial}"
            )
            existingTxWithSameUniqueCode.transaction.pure[Future]
          } else {
            throw Status.ALREADY_EXISTS
              .withDescription(
                s"Mapping with unique key ${mapping.uniqueKey} already exists with a different mapping: $existingTxWithSameUniqueCode"
              )
              .asRuntimeException()
          }
        }
    )
  }

  private def proposeMapping[M <: TopologyMapping: ClassTag](
      store: TopologyStoreId,
      mapping: Either[String, M],
      serial: PositiveInt,
      isProposal: Boolean,
      forceChanges: ForceFlags,
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransaction[TopologyChangeOp, M]] =
    proposeMapping(
      store,
      mapping.valueOr(err => throw new IllegalArgumentException(s"Invalid topology mapping: $err")),
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
      check: TopologyTransactionType => EitherT[Future, TopologyResult[M], TopologyResult[M]],
      update: M => Either[String, M],
      retryFor: RetryFor,
      isProposal: Boolean = false,
      recreateOnAuthorizedStateChange: RecreateOnAuthorizedStateChange =
        RecreateOnAuthorizedStateChange.Recreate,
      forceChanges: ForceFlags = ForceFlags.none,
      waitForAuthorization: Boolean = true,
      maxSubmissionDelay: Option[(Clock, NonNegativeFiniteDuration)] = None,
  )(implicit traceContext: TraceContext): Future[TopologyResult[M]] = {
    val minSubmissionTimeO = maxSubmissionDelay.map { case (clock, maxSubmissionDelay) =>
      val delay: NonNegativeFiniteDuration =
        NonNegativeFiniteDuration.tryFromJavaDuration(
          java.time.Duration
            .ofNanos((math.random() * maxSubmissionDelay.unwrap.toNanos).toLong)
        )
      (clock, clock.now + delay.toInternal)
    }
    withSpan("establish_topology_mapping") { implicit traceContext => _ =>
      logger.info(s"Ensuring that $description")
      retryProvider
        .retry(
          retryFor,
          "establish_topology_mapping_retry",
          description,
          check(AuthorizedState).foldF(
            { case TopologyResult(beforeEstablishedBaseResult, mapping) =>
              (recreateOnAuthorizedStateChange match {
                case RecreateOnAuthorizedStateChange.Abort(expectedSerial)
                    if expectedSerial != beforeEstablishedBaseResult.serial =>
                  Future.failed(AuthorizedStateChanged(beforeEstablishedBaseResult.serial))
                case _ => Future.unit
              })
                .flatMap { _ =>
                  def proposeNewTopologyTransaction = {
                    val updatedMapping = update(mapping)
                    val sleep: Future[Unit] =
                      minSubmissionTimeO.fold(Future.unit) { case (clock, minSubmissionTime) =>
                        logger.info(
                          s"Waiting until $minSubmissionTime before submitting topology transaction"
                        )
                        // This is a noop if minSubmissionTime is in the past.
                        clock.scheduleAt(_ => (), minSubmissionTime).onShutdown(())
                      }
                    sleep.flatMap { _ =>
                      proposeMapping(
                        store,
                        updatedMapping,
                        serial = beforeEstablishedBaseResult.serial + PositiveInt.one,
                        isProposal = isProposal,
                        forceChanges = forceChanges,
                      ).map { signed =>
                        logger.info(
                          s"Submitted proposal ${signed.mapping} for $description, waiting until the proposal gets accepted"
                        )
                        signed.mapping
                      }
                    }
                  }

                  if (isProposal) {
                    check(ProposalSignedByOwnKey)
                      .foldF(
                        _ => {
                          proposeNewTopologyTransaction
                        },
                        existingProposal => {
                          logger.info(
                            s"Found existing proposal ${existingProposal.mapping} for $description, waiting until the proposal gets accepted"
                          )
                          Future.successful(
                            existingProposal.mapping
                          )
                        },
                      )
                      .recoverWith {
                        case ex: StatusRuntimeException
                            if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
                          logger.info(
                            s"No proposal found for $description, submitting new one."
                          )
                          proposeNewTopologyTransaction
                      }
                  } else {
                    proposeNewTopologyTransaction
                  }
                }
                .flatMap { proposal =>
                  retryProvider.retry(
                    retryFor,
                    "check_establish_topology_mapping",
                    s"check established $description",
                    check(TopologyTransactionType.AuthorizedState)
                      .leftFlatMap[TopologyResult[M], RuntimeException] { currentAuthorizedState =>
                        if (
                          currentAuthorizedState.base.serial == beforeEstablishedBaseResult.serial
                        ) {
                          if (isProposal && !waitForAuthorization) {
                            check(TopologyTransactionType.ProposalSignedByOwnKey)
                              .leftMap { res =>
                                Status.FAILED_PRECONDITION
                                  .withDescription(
                                    s"Condition is not yet observed. Waiting for proposal: $proposal, found: $res."
                                  )
                                  .asRuntimeException()
                              }
                          } else {
                            EitherT.leftT(
                              Status.FAILED_PRECONDITION
                                .withDescription(
                                  s"Condition is not yet observed. Proposed: $proposal, found: $currentAuthorizedState."
                                )
                                .asRuntimeException()
                            )
                          }
                        } else {
                          EitherT.leftT[Future, TopologyResult[M]](
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
          store,
          ForceFlags(
            flags*
          ),
          waitToBecomeEffective = None,
        )
    )

  def proposeInitialSequencerSynchronizerState(
      synchronizerId: SynchronizerId,
      active: Seq[SequencerId],
      observers: Seq[SequencerId],
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, SequencerSynchronizerState]] = {
    ensureInitialMapping(
      SequencerSynchronizerState.create(
        synchronizerId,
        PositiveInt.one,
        active,
        observers,
      )
    )
  }

  def ensureSequencerSynchronizerStateAddition(
      synchronizerId: SynchronizerId,
      newActiveSequencer: SequencerId,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerSynchronizerState]] = {
    def sequencerChange(sequencers: Seq[SequencerId]): Seq[SequencerId] = {
      val newSequencers = sequencers :+ newActiveSequencer
      newSequencers.distinct
    }

    ensureSequencerSynchronizerState(
      s"Add sequencer $newActiveSequencer",
      synchronizerId,
      sequencerChange,
      retryFor,
    )
  }

  def ensureSequencerSynchronizerStateRemoval(
      synchronizerId: SynchronizerId,
      sequencerToRemove: SequencerId,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerSynchronizerState]] = {
    def sequencerChange(sequencers: Seq[SequencerId]): Seq[SequencerId] = {
      sequencers.filterNot(_ == sequencerToRemove)
    }
    ensureSequencerSynchronizerState(
      s"Remove sequencer $sequencerToRemove",
      synchronizerId,
      sequencerChange,
      retryFor,
    )
  }

  private def ensureSequencerSynchronizerState(
      description: String,
      synchronizerId: SynchronizerId,
      sequencerChange: Seq[SequencerId] => Seq[SequencerId],
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerSynchronizerState]] = {
    ensureTopologyMapping[SequencerSynchronizerState](
      TopologyStoreId.Synchronizer(synchronizerId),
      description,
      topologyTransactionType =>
        EitherT(
          getSequencerSynchronizerState(synchronizerId, topologyTransactionType).map(result => {
            val newSequencers = sequencerChange(result.mapping.active)
            // we need to check the threshold as well because we reset it to 1 in tests (see ResetSequencerSynchronizerStateThreshold)
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
        SequencerSynchronizerState.create(
          previous.synchronizerId,
          Thresholds
            .sequencerConnectionsSizeThreshold(newSequencers.size),
          newSequencers,
          previous.observers,
        )
      },
      retryFor,
      isProposal = true,
    )
  }

  def proposeInitialMediatorSynchronizerState(
      synchronizerId: SynchronizerId,
      group: NonNegativeInt,
      active: Seq[MediatorId],
      observers: Seq[MediatorId],
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, MediatorSynchronizerState]] =
    ensureInitialMapping(
      MediatorSynchronizerState.create(
        synchronizerId = synchronizerId,
        group = group,
        threshold = PositiveInt.one,
        active = active,
        observers = observers,
      )
    )

  def ensureMediatorSynchronizerStateAdditionProposal(
      synchronizerId: SynchronizerId,
      newActiveMediator: MediatorId,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorSynchronizerState]] = {
    def mediatorChange(mediators: Seq[MediatorId]): Seq[MediatorId] = {
      val newMediators = mediators :+ newActiveMediator
      newMediators.distinct
    }
    ensureMediatorSynchronizerState(
      s"Add mediator $newActiveMediator",
      synchronizerId,
      mediatorChange,
      retryFor,
    )
  }

  def ensureMediatorSynchronizerStateRemovalProposal(
      synchronizerId: SynchronizerId,
      mediatorToRemove: MediatorId,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorSynchronizerState]] = {
    def mediatorChange(mediators: Seq[MediatorId]): Seq[MediatorId] = {
      mediators.filterNot(_ == mediatorToRemove)
    }
    ensureMediatorSynchronizerState(
      s"Remove mediator $mediatorToRemove",
      synchronizerId,
      mediatorChange,
      retryFor,
    )
  }

  private def ensureMediatorSynchronizerState(
      description: String,
      synchronizerId: SynchronizerId,
      mediatorChange: Seq[MediatorId] => Seq[MediatorId],
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorSynchronizerState]] =
    ensureTopologyMapping[MediatorSynchronizerState](
      TopologyStoreId.Synchronizer(synchronizerId),
      description,
      topologyTransactionType =>
        EitherT(
          getMediatorSynchronizerState(synchronizerId, topologyTransactionType).map(result =>
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
        MediatorSynchronizerState.create(
          previous.synchronizerId,
          previous.group,
          Thresholds
            .mediatorDomainStateThreshold(newMediators.size),
          newMediators,
          previous.observers,
        )
      },
      retryFor,
      isProposal = true,
    )

  def proposeInitialDecentralizedNamespaceDefinition(
      namespace: Namespace,
      owners: NonEmpty[Set[Namespace]],
      threshold: PositiveInt,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, DecentralizedNamespaceDefinition]] =
    ensureInitialMapping(
      DecentralizedNamespaceDefinition.create(
        namespace,
        threshold,
        owners,
      )
    )

  def ensureDecentralizedNamespaceDefinitionProposalAccepted(
      synchronizerId: SynchronizerId,
      decentralizedNamespace: Namespace,
      newOwner: Namespace,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DecentralizedNamespaceDefinition]] =
    ensureDecentralizedNamespaceDefinitionOwnerChangeProposalAccepted(
      show"Namespace $newOwner is in owners of DecentralizedNamespaceDefinition",
      synchronizerId,
      decentralizedNamespace,
      _.incl(newOwner),
      retryFor,
    )

  def ensureDecentralizedNamespaceDefinitionRemovalProposal(
      synchronizerId: SynchronizerId,
      decentralizedNamespace: Namespace,
      ownerToRemove: Namespace,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DecentralizedNamespaceDefinition]] =
    ensureDecentralizedNamespaceDefinitionOwnerChangeProposalAccepted(
      show"Namespace $ownerToRemove was removed from the decentralized namespace definition",
      synchronizerId,
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
      retryFor,
    )

  def ensureDecentralizedNamespaceDefinitionOwnerChangeProposalAccepted(
      description: String,
      synchronizerId: SynchronizerId,
      decentralizedNamespace: Namespace,
      ownerChange: NonEmpty[Set[Namespace]] => NonEmpty[Set[Namespace]],
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DecentralizedNamespaceDefinition]] =
    ensureTopologyMapping[DecentralizedNamespaceDefinition](
      TopologyStoreId.Synchronizer(synchronizerId),
      description,
      topologyTransactionType => {
        decentralizedNamespaceDefinitionForNamespace(
          synchronizerId,
          decentralizedNamespace,
          ownerChange,
          topologyTransactionType,
        )
      },
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
      isProposal = true,
    )

  private def decentralizedNamespaceDefinitionForNamespace(
      synchronizerId: SynchronizerId,
      decentralizedNamespace: Namespace,
      ownerChange: NonEmpty[Set[Namespace]] => NonEmpty[Set[Namespace]],
      topologyType: TopologyTransactionType,
  )(implicit tc: TraceContext): EitherT[Future, TopologyResult[
    DecentralizedNamespaceDefinition
  ], TopologyResult[DecentralizedNamespaceDefinition]] = {
    EitherT(
      getDecentralizedNamespaceDefinition(synchronizerId, decentralizedNamespace, topologyType).map(
        result =>
          Either.cond(result.mapping.owners == ownerChange(result.mapping.owners), result, result)
      )
    )
  }

  def proposeInitialDomainParameters(
      synchronizerId: SynchronizerId,
      parameters: DynamicSynchronizerParameters,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, SynchronizerParametersState]] =
    proposeMapping(
      TopologyStoreId.Authorized,
      SynchronizerParametersState(synchronizerId, parameters),
      serial = PositiveInt.one,
      isProposal = false,
    )

  def ensureDomainParameters(
      synchronizerId: SynchronizerId,
      parametersBuilder: DynamicSynchronizerParameters => DynamicSynchronizerParameters,
      forceChanges: ForceFlags = ForceFlags.none,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SynchronizerParametersState]] =
    ensureTopologyMapping[SynchronizerParametersState](
      TopologyStoreId.Synchronizer(synchronizerId),
      "update dynamic domain parameters",
      topologyTransactionType =>
        EitherT(
          getSynchronizerParametersState(synchronizerId, topologyTransactionType).map(state =>
            Either.cond(
              state.mapping.parameters == parametersBuilder(state.mapping.parameters),
              state,
              state,
            )
          )
        ),
      previous => Right(previous.copy(parameters = parametersBuilder(previous.parameters))),
      retryFor = RetryFor.ClientCalls,
      isProposal = true,
      forceChanges = forceChanges,
    )

  def getSynchronizerParametersState(
      synchronizerId: SynchronizerId,
      topologyTransactionType: TopologyTransactionType = AuthorizedState,
  )(implicit tc: TraceContext): Future[TopologyResult[SynchronizerParametersState]] = {
    listSynchronizerParametersState(
      TopologyStoreId.Synchronizer(synchronizerId),
      synchronizerId,
      topologyTransactionType,
      TimeQuery.HeadState,
    )
      .map(_.headOption.getOrElse {
        throw Status.NOT_FOUND
          .withDescription(s"No SynchronizerParametersState state domain $synchronizerId")
          .asRuntimeException
      })
  }

  def listSynchronizerParametersStateHistory(
      synchronizerId: SynchronizerId,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[SynchronizerParametersState]]] = {
    listSynchronizerParametersState(
      TopologyStoreId.Synchronizer(synchronizerId),
      synchronizerId,
      proposals,
      TimeQuery.Range(None, None),
    )
  }

  def listSynchronizerParametersState(
      storeId: TopologyStoreId,
      synchronizerId: SynchronizerId,
      topologyTransactionType: TopologyTransactionType,
      timeQuery: TimeQuery,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[SynchronizerParametersState]]] = {
    runCommandM(
      storeId,
      topologyTransactionType,
      timeQuery,
    )(
      baseQuery =>
        TopologyAdminCommands.Read.ListSynchronizerParametersState(
          baseQuery,
          synchronizerId.filterString,
        ),
      (item: ListSynchronizerParametersStateResult) =>
        TopologyResult(item.context, SynchronizerParametersState(synchronizerId, item.item)),
    )
  }

  def lookupSynchronizerParametersState(
      storeId: TopologyStoreId,
      synchronizerId: SynchronizerId,
      topologyTransactionType: TopologyTransactionType,
  )(implicit tc: TraceContext): Future[Option[TopologyResult[SynchronizerParametersState]]] = {
    runCommandM(
      storeId,
      topologyTransactionType,
      TimeQuery.HeadState,
    )(
      baseQuery =>
        TopologyAdminCommands.Read.ListSynchronizerParametersState(
          baseQuery,
          synchronizerId.filterString,
        ),
      (item: ListSynchronizerParametersStateResult) =>
        TopologyResult(item.context, SynchronizerParametersState(synchronizerId, item.item)),
    ).map(_.headOption)
  }

  private def listNamespaceDelegation(namespace: Namespace, target: Option[SigningPublicKey])(
      implicit traceContext: TraceContext
  ): Future[Seq[TopologyResult[NamespaceDelegation]]] =
    runCmd(
      TopologyAdminCommands.Read.ListNamespaceDelegation(
        BaseQuery(
          store = TopologyStoreId.Authorized,
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
      proposeNamespaceDelegation(namespace, target, isRootDelegation).map(_ => ()),
      logger,
    )

  private def proposeNamespaceDelegation(
      namespace: Namespace,
      target: SigningPublicKey,
      isRootDelegation: Boolean,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, NamespaceDelegation]] =
    ensureInitialMapping(
      NamespaceDelegation.create(
        namespace,
        target,
        if (isRootDelegation) DelegationRestriction.CanSignAllMappings
        else DelegationRestriction.CanSignAllButNamespaceDelegations,
      )
    )

  def initId(id: NodeIdentity)(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(
      TopologyAdminCommands.Init
        .InitId(id.uid.identifier.toProtoPrimitive, id.uid.namespace.toProtoPrimitive, Seq.empty)
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

  def generateKeyPair(name: String, usage: NonEmpty[Set[SigningKeyUsage]])(implicit
      traceContext: TraceContext
  ): Future[SigningPublicKey] = {
    runCmd(VaultAdminCommands.GenerateSigningKey(name, usage, None))
  }

  def generateEncryptionKeyPair(name: String)(implicit
      traceContext: TraceContext
  ): Future[EncryptionPublicKey] = {
    runCmd(VaultAdminCommands.GenerateEncryptionKey(name, None))
  }

  def registerKmsSigningKey(kmsKeyId: String, usage: NonEmpty[Set[SigningKeyUsage]], name: String)(
      implicit traceContext: TraceContext
  ): Future[SigningPublicKey] = {
    runCmd(VaultAdminCommands.RegisterKmsSigningKey(kmsKeyId, usage, name))
  }

  def registerKmsEncryptionKey(kmsKeyId: String, name: String)(implicit
      traceContext: TraceContext
  ): Future[EncryptionPublicKey] = {
    runCmd(VaultAdminCommands.RegisterKmsEncryptionKey(kmsKeyId, name))
  }

  def importKeyPair(keyPair: Array[Byte], name: Option[String])(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(VaultAdminCommands.ImportKeyPair(ByteString.copyFrom(keyPair), name, password = None))
  }

  def listSynchronizerTrustCertificate(synchronizerId: SynchronizerId, member: Member)(implicit
      tc: TraceContext
  ): Future[Seq[TopologyResult[SynchronizerTrustCertificate]]] =
    runCmd(
      TopologyAdminCommands.Read.ListSynchronizerTrustCertificate(
        BaseQuery(
          TopologyStoreId.Synchronizer(synchronizerId),
          proposals = false,
          timeQuery = TimeQuery.HeadState,
          ops = Some(TopologyChangeOp.Replace),
          filterSigningKey = "",
          protocolVersion = None,
        ),
        member.filterString,
      )
    ).map(
      // TODO(#720) Canton currently compares member IDs by string prefix instead of strict equality of
      // member IDs in ListSynchronizerTrustCertificate, so we apply another filter for equality of the member ID
      _.filter(r => r.item.participantId.member.filterString == member.filterString)
        .map(r =>
          TopologyResult(
            r.context,
            r.item,
          )
        )
    )

  def ensureSynchronizerTrustCertificateRemoved(
      retryFor: RetryFor,
      synchronizerId: SynchronizerId,
      member: Member,
  )(implicit tc: TraceContext): Future[Unit] =
    retryProvider.ensureThat(
      retryFor,
      "ensure_domain_trust_certificate_removed",
      s"Remove domain trust certificate for $member on $synchronizerId",
      listSynchronizerTrustCertificate(synchronizerId, member).map {
        case Seq() => Right(())
        case Seq(cert) => Left(cert)
        case certs =>
          throw Status.INTERNAL
            .withDescription(
              s"Expected at most one SynchronizerTrustCertificate for $member but got: $certs"
            )
            .asRuntimeException()
      },
      (previous: TopologyResult[SynchronizerTrustCertificate]) =>
        proposeMapping(
          TopologyStoreId.Synchronizer(synchronizerId),
          previous.mapping,
          previous.base.serial + PositiveInt.one,
          isProposal = false,
          change = TopologyChangeOp.Remove,
        ).map(_ => ()),
      logger,
    )

  def ensurePartyUnhostedFromParticipant(
      retryFor: RetryFor,
      synchronizerId: SynchronizerId,
      partyId: PartyId,
      participant: ParticipantId,
  )(implicit tc: TraceContext): Future[Unit] =
    retryProvider.ensureThat(
      retryFor,
      "ensure_party_unhosted_from_participant",
      s"Remove $participant from party to participant mapping for $partyId on $synchronizerId",
      listPartyToParticipant(
        TopologyStoreId.Synchronizer(synchronizerId).some,
        Some(TopologyChangeOp.Replace),
        filterParty = partyId.filterString,
        filterParticipant = participant.filterString,
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
          TopologyStoreId.Synchronizer(synchronizerId),
          previous.mapping.copy(
            participants = previous.mapping.participants.filterNot(_.participantId == participant)
          ),
          previous.base.serial + PositiveInt.one,
          isProposal = false,
          change = TopologyChangeOp.Replace,
        ).map(_ => ()),
      logger,
    )

  def ensurePartyToParticipantRemoved(
      retryFor: RetryFor,
      synchronizerId: SynchronizerId,
      partyId: PartyId,
      participant: ParticipantId,
      forceChanges: ForceFlags = ForceFlags.none,
  )(implicit tc: TraceContext): Future[Unit] =
    retryProvider.ensureThat(
      retryFor,
      "ensure_party_to_participant_removed",
      s"Remove party to participant for $partyId on $synchronizerId",
      listPartyToParticipant(
        TopologyStoreId.Synchronizer(synchronizerId).some,
        filterParty = partyId.filterString,
        filterParticipant = participant.filterString,
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
          TopologyStoreId.Synchronizer(synchronizerId),
          previous.mapping,
          previous.base.serial + PositiveInt.one,
          isProposal = false,
          change = TopologyChangeOp.Remove,
          forceChanges = forceChanges,
        ).map(_ => ()),
      logger,
    )

  def runCommand[Req, Res, M <: TopologyMapping, Result <: topology.TopologyResult[M]](
      storeId: TopologyStoreId,
      topologyTransactionType: TopologyTransactionType,
      timeQuery: TimeQuery = TimeQuery.HeadState,
      operation: Option[TopologyChangeOp] = None,
  )(
      cmd: BaseQuery => GrpcAdminCommand[Req, Res, Seq[Result]]
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[TopologyResult[M]]] = {
    val mapping: topology.TopologyResult[M] => TopologyResult[M] = result => {
      TopologyResult(result.context, result.item)
    }
    runCommandM[Req, Res, M, Result](
      storeId,
      topologyTransactionType,
      timeQuery,
      operation,
    )(cmd, mapping)
  }

  private def runCommandM[Req, Res, M <: TopologyMapping, Result](
      storeId: TopologyStoreId,
      topologyTransactionType: TopologyTransactionType,
      timeQuery: TimeQuery,
      operation: Option[TopologyChangeOp] = None,
  )(
      cmd: BaseQuery => GrpcAdminCommand[Req, Res, Seq[Result]],
      mapping: Result => TopologyResult[M],
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[TopologyResult[M]]] = {
    val baseQuery = BaseQuery(
      storeId,
      topologyTransactionType.proposals,
      timeQuery = timeQuery,
      ops = operation,
      filterSigningKey = "",
      protocolVersion = None,
    )
    runCmd(
      cmd(baseQuery)
    ).map(results => results.map(mapping)).flatMap { items =>
      topologyTransactionType match {
        // we do the filtering here because canton support filtering by a single key
        case TopologyTransactionType.ProposalSignedByOwnKey =>
          for {
            id <- getId()
            delegations <- listNamespaceDelegation(id.namespace, None).map(
              _.map(_.mapping.target.fingerprint)
            )
          } yield {
            val validSigningKeys = delegations :+ id.fingerprint
            items.filter { proposal =>
              proposal.base.signedBy
                .intersect(validSigningKeys)
                .nonEmpty
            }
          }
        case _ => Future.successful(items)
      }
    }
  }

}

object TopologyAdminConnection {
  sealed trait TopologyTransactionType {
    def proposals: Boolean
  }

  object TopologyTransactionType {

    case object AllProposals extends TopologyTransactionType {
      override def proposals: Boolean = true

    }

    case object ProposalSignedByOwnKey extends TopologyTransactionType {
      override def proposals: Boolean = true

    }

    case object AuthorizedState extends TopologyTransactionType {
      override def proposals: Boolean = false

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
    override val pretty: Pretty[TopologyResult.this.type] = prettyNode(
      "TopologyResult",
      param("base", _.base),
      param("mapping", _.mapping),
    )
  }

  implicit val prettyStore: Pretty[grpc.TopologyStoreId] = prettyOfObject[grpc.TopologyStoreId]

  implicit val prettyBaseResult: Pretty[BaseResult] =
    prettyNode(
      "BaseResult",
      param("storeId", _.storeId),
      param("validFrom", _.validFrom),
      param("validUntil", _.validUntil),
      param("operation", _.operation),
      param("transactionHash", _.transactionHash.hash),
      param("serial", _.serial),
      param("signedBy", _.signedBy),
    )
}
