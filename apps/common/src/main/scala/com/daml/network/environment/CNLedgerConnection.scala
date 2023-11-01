package com.daml.network.environment

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{KillSwitch, KillSwitches, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import cats.syntax.traverse.*
import com.daml.error.utils.ErrorDetails
import com.daml.error.utils.ErrorDetails.ResourceInfoDetail
import com.daml.ledger.api.v1.admin.ObjectMetaOuterClass
import com.daml.ledger.javaapi.data.{
  Command,
  CreatedEvent,
  ExercisedEvent,
  LedgerOffset,
  Transaction,
  TransactionTree,
  User,
}
import com.daml.ledger.javaapi.data.codegen.{Created, Exercised, HasCommands, Update}
import com.daml.network.environment.ledger.api.{
  ActiveContract,
  DedupConfig,
  DedupOffset,
  IncompleteReassignmentEvent,
  LedgerClient,
  NoDedup,
}
import com.daml.network.store.MultiDomainAcsStore.IngestionFilter
import com.daml.network.util.{AssignedContract, Contract, DisclosedContracts}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.error.CantonErrorResource
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  FutureUnlessShutdown,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.messages.LocalReject.ConsistencyRejections.InactiveContracts
import com.daml.ledger.api.v2 as lapi
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset.Value
import com.digitalasset.canton.topology.{DomainId, Identifier, Namespace, PartyId, UniqueIdentifier}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{AkkaUtil, LoggerUtil}
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.FieldMask
import io.grpc.{Status, StatusRuntimeException}

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.implicitNotFound
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success, Try}
import shapeless.<:!<

/** BaseLedgerConnection is a read-only ledger connection, typically used during initialization when we don't
  * want to allow command submissions yet.
  */
class BaseLedgerConnection(
    client: LedgerClient,
    applicationId: String,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit as: ActorSystem, ec: ExecutionContextExecutor)
    extends NamedLogging {

  logger.debug(s"Created connection with applicationId=$applicationId")(
    TraceContext.empty
  )

  import BaseLedgerConnection.*

  // The participant ledger end as opposed to the per-domain ledger end.
  // We use this for synchronization in `UpdateIngestionService`.
  def participantLedgerEnd(): Future[LedgerOffset.Absolute] =
    client.participantLedgerEnd() map {
      case off: LedgerOffset.Absolute => off
      case nonsenseOff =>
        throw new IllegalStateException(s"I was promised a real offset, not $nonsenseOff")
    }

  def ledgerEnd(): Future[Value.Absolute] =
    client.ledgerEnd()

  def activeContracts(
      filter: IngestionFilter,
      offset: ParticipantOffset.Value.Absolute,
  ): Future[
    (
        Seq[ActiveContract],
        Seq[IncompleteReassignmentEvent.Unassign],
        Seq[IncompleteReassignmentEvent.Assign],
    )
  ] = {
    val activeContractsRequest = client.activeContracts(
      lapi.state_service.GetActiveContractsRequest(
        activeAtOffset = offset.value,
        filter = Some(filter.toTransactionFilterAllContractsScala),
      )
    )
    for {
      responseSequence <- activeContractsRequest runWith Sink.seq
      contractStateComponents = responseSequence
        .map(_.contractEntry)
      active = contractStateComponents.collect {
        case lapi.state_service.GetActiveContractsResponse.ContractEntry.ActiveContract(contract) =>
          ActiveContract.fromProto(contract)
      }
      incompleteOut = contractStateComponents.collect {
        case lapi.state_service.GetActiveContractsResponse.ContractEntry
              .IncompleteUnassigned(ev) =>
          IncompleteReassignmentEvent.fromProto(ev)
      }
      incompleteIn = contractStateComponents.collect {
        case lapi.state_service.GetActiveContractsResponse.ContractEntry
              .IncompleteAssigned(ev) =>
          IncompleteReassignmentEvent.fromProto(ev)
      }
    } yield (active, incompleteOut, incompleteIn)
  }

  def getConnectedDomains(party: PartyId): Future[Map[DomainAlias, DomainId]] =
    client.getConnectedDomains(party)

  def updates(
      beginOffset: ParticipantOffset,
      party: PartyId,
  ): Source[LedgerClient.GetTreeUpdatesResponse, NotUsed] =
    client
      .updates(LedgerClient.GetUpdatesRequest(beginOffset, None, party))

  def tryGetTransactionTreeByEventId(
      parties: Seq[PartyId],
      id: String,
  ): Future[TransactionTree] =
    client.tryGetTransactionTreeByEventId(parties.map(_.toProtoPrimitive), id)

  def getOptionalPrimaryParty(
      user: String,
      identityProviderId: Option[String] = None,
  ): Future[Option[PartyId]] = {
    for {
      user <- client
        .getUser(user, identityProviderId)
        .map(Some(_))
      partyId = user.flatMap(_.getPrimaryParty.toScala).map(PartyId.tryFromProtoPrimitive)
    } yield partyId
  }

  def getPrimaryParty(user: String): Future[PartyId] = {
    for {
      partyIdO <- getOptionalPrimaryParty(user)
      partyId = partyIdO.getOrElse(
        throw Status.FAILED_PRECONDITION
          .withDescription(s"User $user has no primary party")
          .asRuntimeException
      )
    } yield partyId
  }

  def ensureUserPrimaryPartyIsAllocated(
      userId: String,
      hint: String,
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext): Future[PartyId] =
    retryProvider.ensureThatO(
      RetryFor.WaitingOnInitDependency,
      s"User $userId has primary party",
      check = getOptionalPrimaryParty(userId),
      establish = for {
        party <- ensurePartyAllocated(hint, None, participantAdminConnection)
        _ <- setUserPrimaryParty(userId, party)
        _ <- grantUserRights(userId, actAsParties = Seq(party), readAsParties = Seq.empty)
      } yield (),
      logger,
    )

  def ensurePartyAllocated(
      hint: String,
      namespaceO: Option[Namespace],
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext) =
    for {
      participantId <- participantAdminConnection.getParticipantId()
      namespace = namespaceO.getOrElse(participantId.uid.namespace)
      partyId = PartyId(
        UniqueIdentifier(
          Identifier.tryCreate(hint),
          namespace,
        )
      )
      _ <- participantAdminConnection
        .ensureInitialPartyToParticipant(
          partyId,
          participantId,
          participantId.uid.namespace.fingerprint,
        )
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        s"Ledger API observers party $partyId",
        client.getParties(Seq(partyId)).map { parties =>
          if (parties.isEmpty)
            throw Status.NOT_FOUND
              .withDescription(s"Party allocation of $partyId not observed on ledger API")
              .asRuntimeException()
        },
        logger,
      )
    } yield partyId

  def waitForPartyOnLedgerApi(
      party: PartyId
  ): Future[Unit] =
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      show"Party $party is observed on ledger API",
      client.getParties(Seq(party)).map { result =>
        if (result.isEmpty) {
          throw Status.NOT_FOUND
            .withDescription(show"Party $party is not visible on ledger API")
            .asRuntimeException()
        }
      },
      logger,
    )

  def getUser(user: String, identityProviderId: Option[String] = None): Future[User] =
    client.getUser(user, identityProviderId)

  private def createPartyAndUser(
      user: String,
      userRights: Seq[User.Right],
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext): Future[PartyId] =
    for {
      party <- ensurePartyAllocated(
        sanitizeUserIdToPartyString(user),
        None,
        participantAdminConnection,
      )
      _ <- createUserWithPrimaryParty(user, party, userRights)
    } yield party

  def createUserWithPrimaryParty(
      user: String,
      party: PartyId,
      userRights: Seq[User.Right],
      identityProviderId: Option[String] = None,
  ): Future[PartyId] = {
    val userId = com.daml.lf.data.Ref.UserId.assertFromString(user)
    val userLf = new User(userId, party.toLf)
    for {
      user <- client
        .getOrCreateUser(
          userLf,
          new User.Right.CanActAs(party.toLf) +: userRights,
          identityProviderId,
        )
      partyId =
        PartyId.tryFromProtoPrimitive(
          user.getPrimaryParty.toScala
            .getOrElse(sys.error(s"user $user was allocated without primary party"))
        )
    } yield partyId
  }

  def setUserPrimaryParty(
      user: String,
      party: PartyId,
      identityProviderId: Option[String] = None,
  ): Future[Unit] =
    client.setUserPrimaryParty(user, party, identityProviderId)

  def ensureUserMetadataAnnotation(userId: String, key: String, value: String, retryFor: RetryFor)(
      implicit ec: ExecutionContext
  ): Future[Unit] =
    ensureUserMetadataAnnotation(userId, Map(key -> value), retryFor)

  def ensureUserMetadataAnnotation(
      userId: String,
      annotations: Map[String, String],
      retryFor: RetryFor,
      identityProviderId: Option[String] = None,
  )(implicit
      ec: ExecutionContext
  ): Future[Unit] =
    retryProvider.ensureThatB(
      retryFor,
      s"user $userId has metadata annotations $annotations",
      client.getUserProto(userId, identityProviderId).map { user =>
        if (!user.hasMetadata)
          false
        else {
          val existingAnnotations = user.getMetadata.getAnnotationsMap.asScala
          annotations.forall { case (k, v) => existingAnnotations.get(k).fold(false)(_ == v) }
        }
      },
      for {
        user <- client.getUserProto(userId, identityProviderId)
        newMetadata =
          if (user.hasMetadata) {
            val metadata = user.getMetadata
            metadata.toBuilder.putAllAnnotations(annotations.asJava).build
          } else {
            ObjectMetaOuterClass.ObjectMeta.newBuilder.putAllAnnotations(annotations.asJava).build
          }
        newUser = user.toBuilder.setMetadata(newMetadata).build
        mask = FieldMask.newBuilder
          .addPaths("metadata.annotations")
          .addPaths("metadata.resource_version")
          .build
        _ <- client.updateUser(newUser, mask)
      } yield (),
      logger,
    )

  private def waitForUserMetadata(
      userId: String,
      key: String,
      identityProviderId: Option[String] = None,
  ): Future[String] =
    retryProvider.getValueWithRetriesNoPretty(
      RetryFor.WaitingOnInitDependency,
      s"metadata field $key of user $userId",
      client.getUserProto(userId, identityProviderId).map { user =>
        if (user.hasMetadata) {
          val metadata = user.getMetadata
          metadata.getAnnotationsMap.asScala.getOrElse(
            key,
            throw Status.NOT_FOUND
              .withDescription(s"User $userId has no metadata annotation for key $key")
              .asRuntimeException(),
          )
        } else {
          throw Status.NOT_FOUND
            .withDescription(s"User $userId has no metadata")
            .asRuntimeException()
        }
      },
      logger,
    )

  def lookupUserMetadata(
      userId: String,
      key: String,
      identityProviderId: Option[String] = None,
  ): Future[Option[String]] =
    client.getUserProto(userId, identityProviderId).map { user =>
      if (user.hasMetadata) {
        user.getMetadata.getAnnotationsMap.asScala.get(key)
      } else {
        None
      }
    }

  // TODO(tech-debt): Factor out user/party allocation and make it robust (current implementation is racy)
  def getOrAllocateParty(
      username: String,
      userRights: Seq[User.Right] = Seq.empty,
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext): Future[PartyId] = {
    for {
      existingPartyId <- getOptionalPrimaryParty(username).recover {
        case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
          None
      }
      partyId <- existingPartyId.fold[Future[PartyId]](
        createPartyAndUser(username, userRights, participantAdminConnection)
      )(
        Future.successful
      )
    } yield partyId
  }

  def getUserActAs(
      username: String
  ): Future[Set[PartyId]] = {
    val userId = com.daml.lf.data.Ref.UserId.assertFromString(username)
    for {
      userRights <- client.listUserRights(userId)
    } yield userRights.collect { case actAs: User.Right.CanActAs =>
      PartyId.tryFromProtoPrimitive(actAs.party)
    }.toSet
  }

  def getUserReadAs(
      username: String
  ): Future[Set[PartyId]] = {
    val userId = com.daml.lf.data.Ref.UserId.assertFromString(username)
    for {
      userRights <- client.listUserRights(userId)
    } yield userRights.collect { case readAs: User.Right.CanReadAs =>
      PartyId.tryFromProtoPrimitive(readAs.party)
    }.toSet
  }

  def grantUserRights(
      user: String,
      actAsParties: Seq[PartyId],
      readAsParties: Seq[PartyId],
  ): Future[Unit] = {
    val grants =
      actAsParties.map(p => new User.Right.CanActAs(p.toLf)) ++ readAsParties.map(p =>
        new User.Right.CanReadAs(p.toLf)
      )
    client.grantUserRights(user, grants)
  }

  def revokeUserRights(
      user: String,
      actAsParties: Seq[PartyId],
      readAsParties: Seq[PartyId],
  ): Future[Unit] = {
    val revokes =
      actAsParties.map(p => new User.Right.CanActAs(p.toLf)) ++ readAsParties.map(p =>
        new User.Right.CanReadAs(p.toLf)
      )
    client.revokeUserRights(user, revokes)
  }

  def listPackages(): Future[Set[String]] =
    client.listPackages().map(_.toSet)

  def waitForPackages(
      requiredPackageIds: Set[String]
  )(implicit traceContext: TraceContext): Future[Unit] = {
    import com.daml.network.util.PrettyInstances.*

    if (requiredPackageIds.isEmpty) {
      logger.debug("Skipping waiting for required packages to be uploaded, as there are none.")
      Future.unit
    } else
      retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        show"packages for $requiredPackageIds are uploaded",
        for {
          actual <- (listPackages(): Future[Set[String]])
        } yield {
          val missing = requiredPackageIds.filter(pkgId => !actual.contains(pkgId))
          if (missing.isEmpty) {
            ()
          } else {
            throw Status.NOT_FOUND.withDescription(show"packages for $missing").asRuntimeException()
          }
        },
        logger,
      )
  }

  // Note that this will only work for apps that run as the SV user, i.e., the sv app, directory and scan.
  def getSvcPartyFromUserMetadata(userId: String): Future[PartyId] =
    waitForUserMetadata(userId, SVC_PARTY_USER_METADATA_KEY).map(
      PartyId.tryFromProtoPrimitive(_)
    )

  // Note that this will only work for apps that run as the SV user, i.e., the sv app, directory and scan.
  def lookupSvcPartyFromUserMetadata(userId: String): Future[Option[PartyId]] =
    lookupUserMetadata(userId, SVC_PARTY_USER_METADATA_KEY).map(
      _.map(PartyId.tryFromProtoPrimitive(_))
    )

  def ensureIdentityProviderConfig(id: String, issuer: String, jwksUrl: String, audience: String)(
      implicit tc: TraceContext
  ): Future[Unit] =
    retryProvider.retry(
      RetryFor.WaitingOnInitDependency,
      show"Create identity provider $id",
      client.createIdentityProviderConfig(id, issuer, jwksUrl, audience).recover {
        case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.ALREADY_EXISTS =>
          logger.info(show"Identity provider $id already exists")
      },
      logger,
    )

}

/** Subscription for reading the ledger */
class CNLedgerSubscription[S](
    source: Source[S, NotUsed],
    mapOperator: Flow[S, ?, ?],
    override protected[this] val retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends RetryProvider.Has
    with FlagCloseableAsync
    with NamedLogging {

  import TraceContext.Implicits.Empty.*

  private val (killSwitch, completed_) = AkkaUtil.runSupervised(
    ex =>
      if (retryProvider.isClosing) {
        logger.info("Ignoring failure to handle transaction, as we are shutting down", ex)
      } else {
        logger.error("Fatally failed to handle transaction", ex)
      },
    source
      // we place the kill switch before the map operator, such that
      // we can shut down the operator quickly and signal upstream to cancel further sending
      .viaMat(KillSwitches.single)(Keep.right)
      .viaMat(mapOperator)(Keep.left)
      // and we get the Future[Done] as completed from the sink so we know when the last message
      // was processed
      .toMat(Sink.ignore)(Keep.both),
  )

  def completed: Future[Done] = completed_

  def initiateShutdown() =
    killSwitch.shutdown()

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    import TraceContext.Implicits.Empty.*
    List[AsyncOrSyncCloseable](
      SyncCloseable(s"terminating ledger api stream", killSwitch.shutdown()),
      AsyncCloseable(
        s"ledger api stream terminated",
        completed.transform {
          case Success(v) => Success(v)
          case Failure(_: StatusRuntimeException) =>
            // don't fail to close if there was a grpc status runtime exception
            // this can happen (i.e. server not available etc.)
            Success(Done)
          case Failure(ex) => Failure(ex)
        },
        timeouts.shutdownShort.unwrap,
      ),
    )
  }
}

/** A ledger connection with full submission functionality */
class CNLedgerConnection(
    client: LedgerClient,
    applicationId: String,
    loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    inactiveContractCallbacks: AtomicReference[Seq[String => Unit]],
    trafficBalanceServiceO: AtomicReference[Option[TrafficBalanceService]],
    completionOffsetCallback: String => Future[Unit],
    packageIdResolver: PackageIdResolver,
)(implicit as: ActorSystem, ec: ExecutionContextExecutor)
    extends BaseLedgerConnection(
      client,
      applicationId,
      loggerFactory,
      retryProvider,
    ) {

  import CNLedgerConnection.*

  private[this] def timeouts = retryProvider.timeouts

  private def callCallbacksOnCompletion[T, U](
      result: Future[T]
  )(getOffsetAndResult: T => (Option[String], U)): Future[U] = {
    import TraceContext.Implicits.Empty.*
    def callCallbacksInternal(result: Try[T]): Unit =
      LoggerUtil.logOnThrow { // in case the callbacks throw.
        result match {
          case Success(_) => ()
          case Failure(ex: io.grpc.StatusRuntimeException)
              if ex.getMessage.contains(InactiveContracts.id) =>
            val statusProto = io.grpc.protobuf.StatusProto.fromThrowable(ex)
            ErrorDetails.from(statusProto).collect {
              case ResourceInfoDetail(contractId, type_)
                  if type_ == CantonErrorResource.ContractId.asString =>
                inactiveContractCallbacks.get().foreach(f => f(contractId))
            }: Unit

          case Failure(_) => ()
        }
      }

    result.onComplete(callCallbacksInternal)
    result.flatMap(x => {
      val (optOffset, result) = getOffsetAndResult(x)
      optOffset match {
        case None => Future.successful(result)
        case Some(offset) =>
          // Wait for the completion offset to have been processed.
          completionOffsetCallback(offset).map(_ => result)
      }
    })
  }

  private def callCallbacksOnCompletionAndWaitForOffset[T, U](
      result: Future[T]
  )(getOffsetAndResult: T => (String, U)): Future[U] =
    callCallbacksOnCompletion(result)(x => {
      val (offset, result) = getOffsetAndResult(x)
      (Some(offset), result)
    })

  private def callCallbacksOnCompletionNoWaitForOffset[T](result: Future[T]): Future[T] =
    callCallbacksOnCompletion(result)(x => (None, x))

  private def verifyEnoughExtraTrafficRemains(domainId: DomainId, commandPriority: CommandPriority)(
      implicit tc: TraceContext
  ): Future[Unit] = {
    trafficBalanceServiceO
      .get()
      .fold(Future.unit)(trafficBalanceService => {
        trafficBalanceService.lookupReservedTraffic(domainId).flatMap {
          case None => Future.unit
          case Some(reservedTraffic) =>
            trafficBalanceService.lookupAvailableTraffic(domainId).flatMap {
              case None => Future.unit
              case Some(availableTraffic) =>
                if (availableTraffic <= reservedTraffic && commandPriority == CommandPriority.Low)
                  throw Status.ABORTED
                    .withDescription(
                      s"Traffic balance below amount reserved for top-ups ($availableTraffic < $reservedTraffic)"
                    )
                    .asRuntimeException()
                else Future.unit
            }
        }
      })
  }

  // When using submitAndWaitForTransaction with a command whose resulting transaction is
  // not visible to the submitting parties, one receives `TRANSACTION_NOT_FOUND`. In case, you run into this consider using
  // one of the other methods in this class that rely on submitAndWaitForTransactionTree instead.

  /** Produce a builder for a command submission.
    *
    * `update` can be one of three things:
    *
    *  1. an ordinary [[Command]] or [[Seq]] thereof,
    *     2. an [[Update]], or
    *     3. the result of [[Contract.Has#exercise]].
    *
    * Supply extra arguments to the builder with the `with*` and `no*` methods.
    * At minimum, you must supply a deduplication strategy with
    * [[submit#withDedup]] or [[submit#noDedup]]; that and other required steps
    * result in compiler errors to the `yield*` methods if they are missing.
    *
    * Finalize the builder with one of the `yield*` methods to actually perform
    * the command submission.  Different calls will compile depending on what
    * `update` you supplied; for example, [[submit#yieldResult]] cannot be used
    * with a plain `Command`, but works with the other two options.
    * [[submit#yieldUnit]] and [[submit#yieldTransaction]] can always be used.
    *
    * {{{
    *  submit(Seq(actor), Seq.empty,
    *         someContract.exercise(_.exerciseFoo(42)))
    *    .withDedup(CommandId(...), task.offset)
    *    .yieldResult(): Future[Exercised[FooResult]]
    * }}}
    */
  def submit[C, DomId](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: C,
      priority: CommandPriority = CommandPriority.Low,
  )(implicit assignment: UpdateAssignment[C, DomId]): submit[C, Any, DomId] =
    new submit(actAs, readAs, update, (), assignment.run(update), DisclosedContracts(), priority)

  final class submit[C, CmdId, DomId] private[CNLedgerConnection] (
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: C,
      commandIdDeduplicationOffset: CmdId,
      domainId: DomId,
      disclosedContracts: DisclosedContracts,
      priority: CommandPriority,
  ) {
    private type DedupNotSpecifiedYet = CmdId =:= Any
    private type DomainIdRequired = DomId <:< DomainId
    private type DomainIdDisallowed = DomId <:< Unit

    private[this] def copy[CmdId0, DomId0](
        commandIdDeduplicationOffset: CmdId0 = this.commandIdDeduplicationOffset,
        domainId: DomId0 = this.domainId,
        disclosedContracts: DisclosedContracts = this.disclosedContracts,
    ): submit[C, CmdId0, DomId0] =
      new submit(
        actAs,
        readAs,
        update,
        commandIdDeduplicationOffset,
        domainId,
        disclosedContracts,
        priority,
      )

    def withDedup(commandId: CommandId, deduplicationOffset: String)(implicit
        cid: DedupNotSpecifiedYet
    ): submit[C, (CommandId, String), DomId] =
      copy(
        commandIdDeduplicationOffset = (commandId, deduplicationOffset)
      )

    def withDedup(commandId: CommandId, deduplicationConfig: DedupConfig)(implicit
        cid: DedupNotSpecifiedYet
    ): submit[C, (CommandId, DedupConfig), DomId] =
      copy(
        commandIdDeduplicationOffset = (commandId, deduplicationConfig)
      )

    def noDedup(implicit cid: DedupNotSpecifiedYet): submit[C, Unit, DomId] =
      copy(commandIdDeduplicationOffset = ())

    @annotation.nowarn("cat=unused&msg=notNE")
    def withDomainId(
        domainId: DomainId,
        disclosedContracts: DisclosedContracts = DisclosedContracts(),
    )(implicit
        noDomIdYet: DomainIdDisallowed,
        // if you statically know you have NE, use withDisclosedContracts instead
        notNE: disclosedContracts.type <:!< DisclosedContracts.NE,
    ): submit[C, CmdId, DomainId] =
      copy(
        domainId = domainId,
        disclosedContracts = disclosedContracts assertOnDomain domainId,
      )

    def withDisclosedContracts(disclosedContracts: DisclosedContracts)(implicit
        dcAllowed: WithDisclosedContracts[C, DomId, disclosedContracts.type]
    ): submit[C, CmdId, DomainId] =
      copy(
        domainId = dcAllowed.inferDomain(update, domainId, disclosedContracts),
        disclosedContracts = disclosedContracts,
      )

    def yieldUnit()(implicit
        tc: TraceContext,
        dom: DomainIdRequired,
        dedup: SubmitDedup[CmdId],
        commandOut: SubmitCommands[C],
    ): Future[Unit] = go()

    def yieldTransaction()(implicit
        tc: TraceContext,
        dom: DomainIdRequired,
        dedup: SubmitDedup[CmdId],
        commandOut: SubmitCommands[C],
    ): Future[Transaction] = go()

    @annotation.nowarn("cat=unused&msg=pickT")
    def yieldResult[Z]()(implicit
        tc: TraceContext,
        dom: DomainIdRequired,
        dedup: SubmitDedup[CmdId],
        commandOut: SubmitCommands[C],
        pickT: YieldResult[C, Z],
        result: SubmitResult[C, Z],
    ): Future[Z] = go()

    @annotation.nowarn("cat=unused&msg=pickT")
    def yieldResultAndOffset[Z]()(implicit
        tc: TraceContext,
        dom: DomainIdRequired,
        dedup: SubmitDedup[CmdId],
        commandOut: SubmitCommands[C],
        pickT: YieldResult[C, Z],
        result: SubmitResult[C, (String, Z)],
    ): Future[(String, Z)] =
      go()

    private[this] def go[Z]()(implicit
        tc: TraceContext,
        dom: DomainIdRequired,
        dedup: SubmitDedup[CmdId],
        commandOut: SubmitCommands[C],
        result: SubmitResult[C, Z],
    ): Future[Z] = {
      verifyEnoughExtraTrafficRemains(domainId, priority)
        .flatMap(_ => commandOut.run(update).toList.traverse(packageIdResolver.resolvePackageId(_)))
        .flatMap { commands =>
          import SubmitResult.*, LedgerClient.SubmitAndWaitFor as WF
          val (commandId, deduplicationConfig) = dedup.split(commandIdDeduplicationOffset)

          def clientSubmit[W, U](waitFor: WF[W])(getOffsetAndResult: W => (String, U)): Future[U] =
            callCallbacksOnCompletionAndWaitForOffset(
              client.submitAndWait(
                workflowId = CNLedgerConnection.domainIdToWorkflowId(domainId),
                applicationId = applicationId,
                commandId = commandId,
                deduplicationConfig = deduplicationConfig,
                actAs = actAs.map(_.toProtoPrimitive),
                readAs = readAs.map(_.toProtoPrimitive),
                commands = commands,
                disclosedContracts = disclosedContracts assertOnDomain domainId,
                waitFor = waitFor,
              )
            )(getOffsetAndResult)

          @annotation.tailrec
          def interpretResult[C0, Z0](update: C0, result: SubmitResult[C0, Z0]): Future[Z0] =
            result match {
              case _: Ignored =>
                clientSubmit(WF.CompletionOffset)(offset => (offset, (): Z0))
              case _: JustTransaction =>
                clientSubmit(WF.Transaction)(tx => (tx.getOffset, tx))
              case k: ResultAndOffset[t, Z0] =>
                for {
                  tree <- clientSubmit(WF.TransactionTree)(tx => (tx.getOffset, tx))
                } yield k.continue(
                  tree.getOffset,
                  decodeExerciseResult(update, tree),
                )
              case wrapper: Contramap[C0, u, Z0] =>
                interpretResult(wrapper.g(update), wrapper.rec)
            }

          interpretResult(update, result)
        }
    }
  }

  def submitReassignmentAndWaitNoDedup(
      submitter: PartyId,
      command: LedgerClient.ReassignmentCommand,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val commandId = UUID.randomUUID().toString()
    logger.debug(s"reassignment $commandId is for $command")

    val (ks, completion) = cancelIfFailed(
      client
        .completions(applicationId, Seq(submitter), begin = None)
        .wireTap(csr => logger.trace(s"completions while awaiting reassignment $commandId: $csr"))
    )(awaitCompletion(applicationId = applicationId, commandId = commandId))(
      // We call the callbacks for handling stale contract errors here, but wait for the offset
      // ingestion at which the completion is reported.
      callCallbacksOnCompletionNoWaitForOffset(
        client
          .submitReassignment(
            applicationId,
            commandId,
            submissionId = commandId,
            submitter,
            command,
          )
          .andThen { _ =>
            logger.info(
              s"Submitted reassignment to ledger, waiting for completion: commandId: $commandId"
            )
          }
      )
    )

    retryProvider
      .waitUnlessShutdown(completion)
      .flatMap { case ((offset, _), ()) =>
        FutureUnlessShutdown.outcomeF(offset match {
          case absolute: LedgerOffset.Absolute =>
            completionOffsetCallback(absolute.getOffset).map(_ => ())
          case other =>
            logger.warn(s"Encountered unexpected non-absolute ledger offset $other")
            Future.unit
        })
      }
      .onShutdown {
        logger.debug(
          s"shutting down while awaiting completion of reassignment $commandId; pretending a completion arrived"
        )
        ks.shutdown()
        ()
      }
  }

  // simulate the completion check of command service; future only yields
  // successfully if the completion was OK
  private[this] def awaitCompletion(
      applicationId: String,
      commandId: String,
  )(implicit
      traceContext: TraceContext
  ): Sink[LedgerClient.CompletionStreamResponse, Future[
    (LedgerOffset, LedgerClient.Completion)
  ]] = {
    import io.grpc.Status.{DEADLINE_EXCEEDED, UNAVAILABLE}
    val howLongToWait = timeouts.network.asFiniteApproximation
    Flow[LedgerClient.CompletionStreamResponse]
      .completionTimeout(howLongToWait)
      .mapError { case te: concurrent.TimeoutException =>
        DEADLINE_EXCEEDED
          .withCause(te)
          .augmentDescription(s"timeout while awaiting completion of reassignment $commandId")
          .asRuntimeException()
      }
      .collect {
        case LedgerClient.CompletionStreamResponse(laterOffset, completion)
            if completion.matchesSubmission(applicationId, commandId, commandId) =>
          (laterOffset, completion)
      }
      .take(1)
      .wireTap(cpl => logger.debug(s"selected completion for $commandId: $cpl"))
      .toMat(
        Sink
          .headOption[(LedgerOffset, LedgerClient.Completion)]
          .mapMaterializedValue(_ map (_ map { case result @ (_, completion) =>
            if (completion.status.isOk) result
            else throw completion.status.asRuntimeException()
          } getOrElse {
            throw UNAVAILABLE
              .augmentDescription(
                s"participant stopped while awaiting completion of reassignment $commandId"
              )
              .asRuntimeException()
          }))
      )(Keep.right)
  }

  // run in connected to out first, *then start* fb
  // but proactively cancel the in->out graph if fb fails
  private[this] def cancelIfFailed[A, E, B](in: Source[E, _])(out: Sink[E, Future[A]])(
      fb: => Future[B]
  ): (KillSwitch, Future[(A, B)]) = {
    val (ks, fa) = in.viaMat(KillSwitches.single)(Keep.right).toMat(out)(Keep.both).run()
    ks -> (fa zip fb.transform(
      identity,
      { t =>
        ks.abort(t)
        t
      },
    ))
  }

}

object BaseLedgerConnection {

  val SVC_PARTY_USER_METADATA_KEY: String = "sv.app.network.canton.global/svc_party"

  val APP_MANAGER_IDENTITY_PROVIDER_ID: String = "app_manager"

  val APP_MANAGER_ISSUER: String = "app_manager"

  /** In a number of places we want to use a user id in a place where a `PartyString` expected, e.g.,
    * in party id hints and in workflow ids. However, the allowed set of characters is slightly different so
    * this function can be used to perform the necessary escaping. Note that PartyString is more restrictive than
    * LedgerString so this can also be used to escape to ledger strings.
    */
  def sanitizeUserIdToPartyString(userId: String): String = {
    // We escape invalid characters using _hexcode(invalidchar)
    // See https://github.com/digital-asset/daml/blob/dfc8619e35969ce30daa2427d7318bf70bb75386/daml-lf/data/src/main/scala/com/digitalasset/daml/lf/data/IdString.scala#L320
    // for allowed characters.
    userId.view.flatMap {
      case c if c >= 'a' && c <= 'z' => Seq(c)
      case c if c >= 'A' && c <= 'Z' => Seq(c)
      case c if c >= '0' && c <= '9' => Seq(c)
      case c if c == ':' || c == '-' || c == ' ' => Seq(c)
      case '_' => Seq('_', '_')
      case c =>
        '_' +: "%04x".format(c.toInt)
    }.mkString
  }
}

object CNLedgerConnection {

  def uniqueId: String = UUID.randomUUID.toString

  /** Abstract representation of a command-id for deduplication.
    *
    * @param methodName    : fully-classified name of the method whose calls should be deduplicated,
    *                      e.g., "com.daml.network.directory.createDirectoryEntry". DON'T USE [[io.functionmeta.functionFullName]] here,
    *                      as it is not consistent across updates and restarts.
    * @param parties       : list of parties whose method calls should be considered distinct,
    *                      e.g., "Seq(directoryProvider)"
    * @param discriminator : additional discriminator for method calls,
    *                      e.g., "digitalasset.cn" in case of deduplicating directory entry requests relating to directory name "digitalasset.cn". Beware of naive concatenation
    *                      strings for discriminators. Always ensure that the encoding is injective.
    */
  case class CommandId(methodName: String, parties: Seq[PartyId], discriminator: String = "") {
    require(!methodName.contains('_'))

    // NOTE: avoid changing this computation, as otherwise some commands might not get properly deduplicated
    // on an app upgrade.
    def commandIdForSubmission: String = {
      val str = parties
        .map(_.toProtoPrimitive)
        .prepended(
          parties.length.toString
        ) // prepend length to avoid suffixes interfering with party mapping, e.g., otherwise we have
        // CommandId("myMethod", Seq(alice), "bob").commandIdForSubmission == CommandId("myMethod", Seq(alice,bob), "").commandIdForSubmission
        .appended(discriminator)
        .mkString("/")
      // Digest is not thread safe, create a new one each time.
      val hashFun = MessageDigest.getInstance("SHA-256")
      val hash = hashFun.digest(str.getBytes("UTF-8")).map("%02x".format(_)).mkString
      s"${methodName}_$hash"
    }
  }

  // Note: currently it is not possible to directly specify the target domain on command submissions.
  // Instead, Canton looks at the workflow ID field, and interprets it as the target domain if it starts with "domain-id:"
  def domainIdToWorkflowId(id: DomainId): String =
    s"domain-id:${id.toProtoPrimitive}"

  def decodeExerciseResult[T](
      update: Update[T],
      transaction: TransactionTree,
  ): T = {
    val rootEventIds = transaction.getRootEventIds.asScala.toSeq
    if (rootEventIds.size == 1) {
      val eventByIds = transaction.getEventsById.asScala
      val event = eventByIds(rootEventIds(0))
      update.foldUpdate[T](
        new Update.FoldUpdate[T, T] {
          override def created[CtId](create: Update.CreateUpdate[CtId, T]): T = {
            val createdEvent = event match {
              case created: CreatedEvent => created
              case _ =>
                throw new IllegalArgumentException(s"Expected CreatedEvent but got $event")
            }
            create.k(Created.fromEvent(create.createdContractId, createdEvent))
          }

          override def exercised[R](exercise: Update.ExerciseUpdate[R, T]): T = {
            val exercisedEvent = event match {
              case exercised: ExercisedEvent => exercised
              case _ =>
                throw new IllegalArgumentException(s"Expected ExercisedEvent but got $event")
            }
            exercise.k(Exercised.fromEvent(exercise.returnTypeDecoder, exercisedEvent))
          }
        }
      )
    } else {
      throw new IllegalArgumentException(
        s"Expected exactly one root event id but got ${rootEventIds.size}"
      )
    }
  }

  @implicitNotFound(
    "${CmdId} isn't a deduplication configuration or Unit. Did you call withDedup or noDedup?"
  )
  final case class SubmitDedup[-CmdId](
      private[CNLedgerConnection] val split: CmdId => (String, DedupConfig)
  )
  object SubmitDedup {
    implicit val dedupOffset: SubmitDedup[(CommandId, String)] = SubmitDedup { case (cid, offset) =>
      (cid.commandIdForSubmission, DedupOffset(offset))
    }
    implicit val dedupConfig: SubmitDedup[(CommandId, DedupConfig)] = SubmitDedup {
      case (cid, dc) =>
        (cid.commandIdForSubmission, dc)
    }
    implicit val noDedup: SubmitDedup[Unit] =
      SubmitDedup(_ => (CNLedgerConnection.uniqueId, NoDedup))
  }

  /** Some `update`s can determine the domain ID immediately; this typeclass
    * determines when this has happened.
    */
  final case class UpdateAssignment[-C, +DomId](private[CNLedgerConnection] val run: C => DomId)
  object UpdateAssignment extends UpdateAssignmentLow {
    implicit val assigned
        : UpdateAssignment[Contract.Exercising[AssignedContract[?, ?], ?], DomainId] =
      UpdateAssignment(_.origin.domain)
  }
  sealed abstract class UpdateAssignmentLow {
    implicit val noInfo: UpdateAssignment[Any, Unit] = UpdateAssignment(Function const (()))
  }

  @implicitNotFound(
    "withDisclosedContracts can only be used when ${C} is .exercise on an AssignedContract, or ${DomId} is Unit and ${Arg} is statically non-empty"
  )
  final case class WithDisclosedContracts[-C, -DomId, -Arg](
      private[CNLedgerConnection] val inferDomain: (C, DomId, Arg) => DomainId
  )
  object WithDisclosedContracts {
    @annotation.nowarn("cat=unused&msg=C")
    implicit def exercised[C](implicit
        C: UpdateAssignment[C, DomainId] // proves that DomainId derives directly from C
    ): WithDisclosedContracts[C, DomainId, DisclosedContracts] =
      WithDisclosedContracts((_, domainId, _) => domainId)
    implicit val nonEmpty: WithDisclosedContracts[Any, Unit, DisclosedContracts.NE] =
      WithDisclosedContracts((_, _, dc) => dc.assignedDomain)
  }

  /** A type-determining typeclass for [[CNLedgerConnection#submit#yieldResult]].
    * That method requires an effective functional dependency `C -> Z`, but
    * [[SubmitCommands]] cannot supply that.  The fundep is supplied by this
    * typeclass instead.
    */
  @implicitNotFound("${C} doesn't contain an Update that can yield a result")
  sealed abstract class YieldResult[-C, +Z]
  object YieldResult {
    private[this] object OnlyInstance extends YieldResult[Any, Nothing]
    implicit def update[T]: YieldResult[Update[T], T] = OnlyInstance
    implicit def exercising[T]: YieldResult[Contract.Exercising[Any, T], T] =
      OnlyInstance
  }

  /** Just a folder for the commands in an `update` supplied to `#submit`. */
  @implicitNotFound(
    "${C} isn't a single update, contract exercise or list of commands. The update argument to submit must be one of these"
  )
  final case class SubmitCommands[-C](private[CNLedgerConnection] val run: C => Seq[Command])
  object SubmitCommands {
    implicit val single: SubmitCommands[HasCommands] = SubmitCommands(_.commands().asScala.toSeq)
    implicit val multiple: SubmitCommands[Seq[HasCommands]] =
      SubmitCommands(cs => HasCommands.toCommands(cs.asJava).asScala.toSeq)
    implicit val exercise: SubmitCommands[Contract.Exercising[Any, ?]] =
      SubmitCommands(e => single.run(e.update))
  }

  @implicitNotFound("${C} can't be interpreted to produce ${Z} from the command service")
  sealed abstract class SubmitResult[-C, +Z]
  object SubmitResult {
    private[CNLedgerConnection] final class Ignored extends SubmitResult[Any, Unit]
    implicit val Ignored: SubmitResult[Any, Unit] = new Ignored
    private[CNLedgerConnection] final class JustTransaction extends SubmitResult[Any, Transaction]
    implicit val JustTransaction: SubmitResult[Any, Transaction] = new JustTransaction
    implicit def resultAndOffset[T]: SubmitResult[Update[T], (String, T)] = new ResultAndOffset()
    implicit def onlyResult[T]: SubmitResult[Update[T], T] = new ResultAndOffset((_, t) => t)
    implicit def exercising[T, Z](implicit
        rec: SubmitResult[Update[T], Z]
    ): SubmitResult[Contract.Exercising[Any, T], Z] = new Contramap(_.update, rec)

    private[CNLedgerConnection] final class ResultAndOffset[T, +Z](
        val continue: (String, T) => Z = (_: String, _: T)
    ) extends SubmitResult[Update[T], Z]

    private[CNLedgerConnection] final class Contramap[-C, U, +Z](
        val g: C => U,
        val rec: SubmitResult[U, Z],
    ) extends SubmitResult[C, Z]
  }
}

sealed trait CommandPriority { def name: String }
object CommandPriority {
  case object High extends CommandPriority { val name = "High" }
  case object Low extends CommandPriority { val name = "Low" }
}
