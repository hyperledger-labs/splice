// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.{EitherT, OptionT}
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId}
import cats.syntax.either.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommands.Init.GetIdResult
import com.digitalasset.canton.admin.api.client.commands.{
  SynchronizerTimeCommands,
  TopologyAdminCommands,
  VaultAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.topology.{
  BaseResult,
  ListNamespaceDelegationResult,
  ListOwnerToKeyMappingResult,
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
import com.digitalasset.canton.time.FetchTimeResponse
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.admin.grpc
import com.digitalasset.canton.topology.admin.grpc.{
  BaseQuery,
  TopologyStoreId as ProtoTopologyStoreId,
}
import com.digitalasset.canton.topology.admin.v30.ExportTopologySnapshotResponse
import com.digitalasset.canton.topology.store.TimeQuery.HeadState
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransaction,
  TimeQuery,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.RetryProvider.QuietNonRetryableException
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.{
  AuthorizedStateChanged,
  TopologyTransactionType,
}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.implicitConversions
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

  private implicit def internalStoreIdToProto(internal: TopologyStoreId): ProtoTopologyStoreId =
    ProtoTopologyStoreId.fromInternal(internal)

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
        Some(synchronizerId),
        freshnessBound =
          com.digitalasset.canton.time.NonNegativeFiniteDuration.fromConfig(maxDomainTimeLag),
        timeout = timeout,
      )
    )

  def listPartyToParticipant(
      store: Option[TopologyStoreId] = None,
      // list only active (non-removed) mappings by default; this matches the Canton console defaults
      operation: Option[TopologyChangeOp] = Some(TopologyChangeOp.Replace),
      filterParty: String = "",
      filterParticipant: String = "",
      timeQuery: TimeQuery = TimeQuery.HeadState,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipant]]] = {
    runCmd(
      TopologyAdminCommands.Read.ListPartyToParticipant(
        BaseQuery(
          store.map(internalStoreIdToProto),
          proposals = proposals.proposals,
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
          store = filterStore,
          proposals = proposals.proposals,
          timeQuery,
          operation,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParty.fold("")(_.toProtoPrimitive),
      )
    ).map(_.map(r => TopologyResult(r.context, r.item)))
  }

  private def findPartyToParticipant(
      synchronizerId: SynchronizerId,
      partyId: PartyId,
      operation: Option[TopologyChangeOp],
  )(implicit traceContext: TraceContext): OptionT[Future, TopologyResult[PartyToParticipant]] =
    OptionT(
      listPartyToParticipant(
        store = Some(TopologyStoreId.SynchronizerStore(synchronizerId)),
        filterParty = partyId.filterString,
        operation = operation,
      ).map { txs =>
        txs.headOption
      }
    )

  def getPartyToParticipant(
      synchronizerId: SynchronizerId,
      partyId: PartyId,
      // get only active (non-removed) mappings by default; this matches the Canton console defaults
      operation: Option[TopologyChangeOp] = Some(TopologyChangeOp.Replace),
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] =
    findPartyToParticipant(synchronizerId, partyId, operation).getOrElse {
      throw Status.NOT_FOUND
        .withDescription(s"No PartyToParticipant state for $partyId on domain $synchronizerId")
        .asRuntimeException
    }

  def listSequencerSynchronizerState(
      synchronizerId: SynchronizerId,
      timeQuery: TimeQuery,
      proposals: Boolean = false,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[SequencerSynchronizerState]]] =
    listSequencerSynchronizerState(
      TopologyStoreId.SynchronizerStore(synchronizerId),
      synchronizerId,
      timeQuery,
      proposals,
    )

  def listSequencerSynchronizerState(
      store: TopologyStoreId,
      synchronizerId: SynchronizerId,
      timeQuery: TimeQuery,
      proposals: Boolean,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[SequencerSynchronizerState]]] =
    runCmd(
      TopologyAdminCommands.Read.SequencerSynchronizerState(
        BaseQuery(
          store = store,
          proposals = proposals,
          timeQuery = timeQuery,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterSynchronizerId = synchronizerId.filterString,
      )
    ).map { txs =>
      txs.map(res => TopologyResult(res.context, res.item))
    }

  def getSequencerSynchronizerState(synchronizerId: SynchronizerId, proposals: Boolean = false)(
      implicit traceContext: TraceContext
  ): Future[TopologyResult[SequencerSynchronizerState]] =
    listSequencerSynchronizerState(synchronizerId, HeadState, proposals).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No sequencer state for domain $synchronizerId")
            .asRuntimeException()
        )
    }

  def getMediatorSynchronizerState(synchronizerId: SynchronizerId)(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[MediatorSynchronizerState]] =
    listMediatorSynchronizerState(
      TopologyStoreId.SynchronizerStore(synchronizerId),
      synchronizerId,
      proposals = false,
    )
      .map { txs =>
        txs.headOption
          .getOrElse(
            throw Status.NOT_FOUND
              .withDescription(s"No mediator state for domain $synchronizerId")
              .asRuntimeException()
          )
      }

  def listMediatorSynchronizerState(
      store: TopologyStoreId,
      synchronizerId: SynchronizerId,
      proposals: Boolean,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[MediatorSynchronizerState]]] = {
    runCmd(
      TopologyAdminCommands.Read.MediatorSynchronizerState(
        BaseQuery(
          store = store,
          proposals = proposals,
          timeQuery = TimeQuery.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterSynchronizerId = synchronizerId.filterString,
      )
    )
  }.map { txs =>
    txs.map { tx => TopologyResult(tx.context, tx.item) }
  }

  def getDecentralizedNamespaceDefinition(
      synchronizerId: SynchronizerId,
      decentralizedNamespace: Namespace,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[DecentralizedNamespaceDefinition]] =
    listDecentralizedNamespaceDefinition(synchronizerId, decentralizedNamespace).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(
              show"No decentralized namespace definition for $decentralizedNamespace on domain $synchronizerId"
            )
            .asRuntimeException()
        )
    }

  def listDecentralizedNamespaceDefinition(
      synchronizerId: SynchronizerId,
      decentralizedNamespace: Namespace,
      proposals: TopologyTransactionType = AuthorizedState,
      timeQuery: TimeQuery = TimeQuery.HeadState,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[DecentralizedNamespaceDefinition]]] = {
    runCmd(
      TopologyAdminCommands.Read.ListDecentralizedNamespaceDefinition(
        BaseQuery(
          store = TopologyStoreId.SynchronizerStore(synchronizerId),
          proposals = proposals.proposals,
          timeQuery = timeQuery,
          ops = None,
          filterSigningKey = "",
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
                (keys ++ mapping.mapping.keys).distinct,
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
      TopologyStoreId.AuthorizedStore,
      OwnerToKeyMapping(
        member,
        keys = keys,
      ),
      serial = serial,
      isProposal = false,
      change = TopologyChangeOp.Replace,
      forceChanges = ForceFlags.none,
    )

  def listOwnerToKeyMapping(member: Member)(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[OwnerToKeyMapping]]] =
    runCmd(
      TopologyAdminCommands.Read.ListOwnerToKeyMapping(
        BaseQuery(
          store = AuthorizedStore,
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
      TopologyStoreId.AuthorizedStore,
      proposals = false,
      excludeMappings = TopologyMapping.Code.all.diff(
        Seq(
          TopologyMapping.Code.NamespaceDelegation,
          TopologyMapping.Code.OwnerToKeyMapping,
          // only relevant for participants
          TopologyMapping.Code.VettedPackages,
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

  private def ensureInitialMapping[M <: TopologyMapping: ClassTag](
      mappingE: Either[String, M]
  )(implicit traceContext: TraceContext): Future[SignedTopologyTransaction[TopologyChangeOp, M]] = {
    val mapping =
      mappingE.valueOr(err => throw new IllegalArgumentException(s"Invalid topology mapping: $err"))
    listAllTransactions(
      TopologyStoreId.AuthorizedStore,
      includeMappings = Set(
        mapping.code
      ),
    ).flatMap(existing =>
      existing
        .find(_.mapping == mapping)
        .flatMap(_.selectMapping[M])
        .fold({
          logger.info(
            s"Proposing initial mapping for ${mapping.code} with serial 1: $mapping"
          )
          proposeMapping(
            TopologyStoreId.AuthorizedStore,
            mapping,
            PositiveInt.one,
            isProposal = false,
          )
        }) { existingTx =>
          logger.info(
            s"Existing mapping found for ${mapping.code}: $mapping, returning existing transaction with serial ${existingTx.serial}"
          )
          existingTx.transaction.pure[Future]
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
      check: => EitherT[Future, TopologyResult[M], TopologyResult[M]],
      update: M => Either[String, M],
      retryFor: RetryFor,
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
      TopologyStoreId.SynchronizerStore(synchronizerId),
      description,
      EitherT(
        getSequencerSynchronizerState(synchronizerId).map(result => {
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
      TopologyStoreId.SynchronizerStore(synchronizerId),
      description,
      EitherT(
        getMediatorSynchronizerState(synchronizerId).map(result =>
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
      TopologyStoreId.SynchronizerStore(synchronizerId),
      description,
      decentralizedNamespaceDefinitionForNamespace(
        synchronizerId,
        decentralizedNamespace,
        ownerChange,
      ),
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
  )(implicit tc: TraceContext) = {
    EitherT(
      getDecentralizedNamespaceDefinition(synchronizerId, decentralizedNamespace).map(result =>
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
      TopologyStoreId.AuthorizedStore,
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
      TopologyStoreId.SynchronizerStore(synchronizerId),
      "update dynamic domain parameters",
      EitherT(
        getSynchronizerParametersState(synchronizerId).map(state =>
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
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit tc: TraceContext): Future[TopologyResult[SynchronizerParametersState]] = {
    listSynchronizerParametersState(
      TopologyStoreId.SynchronizerStore(synchronizerId),
      synchronizerId,
      proposals,
      TimeQuery.HeadState,
    )
      .map(_.headOption.getOrElse {
        throw Status.NOT_FOUND
          .withDescription(s"No SynchronizerParametersState state domain $synchronizerId")
          .asRuntimeException
      })
  }

  def listSynchronizerParametersState(
      synchronizerId: SynchronizerId,
      proposals: TopologyTransactionType = AuthorizedState,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[SynchronizerParametersState]]] = {
    listSynchronizerParametersState(
      TopologyStoreId.SynchronizerStore(synchronizerId),
      synchronizerId,
      proposals,
      TimeQuery.Range(None, None),
    )
  }

  def listSynchronizerParametersState(
      storeId: TopologyStoreId,
      synchronizerId: SynchronizerId,
      proposals: TopologyTransactionType,
      timeQuery: TimeQuery,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[SynchronizerParametersState]]] = {
    runCmd(
      TopologyAdminCommands.Read.SynchronizerParametersState(
        BaseQuery(
          storeId,
          proposals = proposals.proposals,
          timeQuery,
          None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        synchronizerId.filterString,
      )
    ).map(
      _.map(r => TopologyResult(r.context, SynchronizerParametersState(synchronizerId, r.item)))
    )
  }

  def listNamespaceDelegation(namespace: Namespace, target: Option[SigningPublicKey])(implicit
      traceContext: TraceContext
  ): Future[Seq[TopologyResult[NamespaceDelegation]]] =
    runCmd(
      TopologyAdminCommands.Read.ListNamespaceDelegation(
        BaseQuery(
          store = AuthorizedStore,
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

  private def listSynchronizerTrustCertificate(synchronizerId: SynchronizerId, member: Member)(
      implicit tc: TraceContext
  ): Future[Seq[TopologyResult[SynchronizerTrustCertificate]]] =
    runCmd(
      TopologyAdminCommands.Read.ListSynchronizerTrustCertificate(
        BaseQuery(
          TopologyStoreId.SynchronizerStore(synchronizerId),
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
          TopologyStoreId.SynchronizerStore(synchronizerId),
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
        TopologyStoreId.SynchronizerStore(synchronizerId).some,
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
          TopologyStoreId.SynchronizerStore(synchronizerId),
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
        TopologyStoreId.SynchronizerStore(synchronizerId).some,
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
          TopologyStoreId.SynchronizerStore(synchronizerId),
          previous.mapping,
          previous.base.serial + PositiveInt.one,
          isProposal = false,
          change = TopologyChangeOp.Remove,
          forceChanges = forceChanges,
        ).map(_ => ()),
      logger,
    )
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
      param("transactionHash", _.transactionHash),
      param("serial", _.serial),
      param("signedBy", _.signedBy),
    )
}
