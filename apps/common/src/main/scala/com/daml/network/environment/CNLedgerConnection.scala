package com.daml.network.environment

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{KillSwitch, KillSwitches, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.daml.error.utils.ErrorDetails
import com.daml.error.utils.ErrorDetails.ResourceInfoDetail
import com.daml.lf.archive.DarParser
import com.daml.ledger.javaapi.data.{
  Command,
  CreatedEvent,
  ExercisedEvent,
  LedgerOffset,
  Transaction,
  TransactionTree,
  User,
}
import com.daml.ledger.javaapi.data.codegen.{Created, Exercised, Update}
import com.daml.network.environment.ledger.api.{
  ActiveContract,
  DedupConfig,
  DedupOffset,
  InFlightTransferOutEvent,
  LedgerClient,
  NoDedup,
}
import com.daml.network.store.MultiDomainAcsStore.IngestionFilter
import com.daml.network.util.{UploadablePackage, DisclosedContracts}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.error.CantonErrorResource
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  SyncCloseable,
  FutureUnlessShutdown,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.messages.LocalReject.ConsistencyRejections.InactiveContracts
import com.digitalasset.canton.participant.protocol.v0.multidomain
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{AkkaUtil, LoggerUtil}
import com.google.protobuf.ByteString
import io.grpc.StatusRuntimeException

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success, Try}

class CNLedgerConnection(
    client: LedgerClient,
    applicationId: String,
    protected val loggerFactory: NamedLoggerFactory,
    protected val timeouts: ProcessingTimeout,
    retryProvider: RetryProvider,
    inactiveContractCallbacks: AtomicReference[Seq[String => Unit]],
    completionOffsetCallback: String => Future[Unit],
)(implicit as: ActorSystem, ec: ExecutionContextExecutor)
    extends NamedLogging {

  logger.debug(s"Created connection with applicationId=$applicationId")(
    TraceContext.empty
  )

  import CNLedgerConnection.*

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

  // The participant ledger end as opposed to the per-domain ledger end.
  // We use this for synchronization in `UpdateIngestionService`.
  def participantLedgerEnd(): Future[LedgerOffset.Absolute] =
    client.participantLedgerEnd() map {
      case off: LedgerOffset.Absolute => off
      case nonsenseOff =>
        throw new IllegalStateException(s"I was promised a real offset, not $nonsenseOff")
    }

  def ledgerEnd(): Future[LedgerOffset.Absolute] =
    client.ledgerEnd()

  def submitCommandsNoDedup(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      domainId: DomainId,
      disclosedContracts: DisclosedContracts = DisclosedContracts(),
  ): Future[Unit] = {
    callCallbacksOnCompletionAndWaitForOffset(
      client
        .submitAndWait(
          workflowId = CNLedgerConnection.domainIdToWorkflowId(domainId),
          applicationId = applicationId,
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          commandId = uniqueId,
          deduplicationConfig = NoDedup,
          disclosedContracts = disclosedContracts assertOnDomain domainId,
        )
    )(offset => (offset, ()))
  }

  // When using submitAndWaitForTransaction with a command whose resulting transaction is
  // not visible to the submitting parties, one receives `TRANSACTION_NOT_FOUND`. In case, you run into this consider using
  // one of the other methods in this class that rely on submitAndWaitForTransactionTree instead.

  def submitCommands(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      commandId: CommandId,
      deduplicationOffset: String,
      domainId: DomainId,
      disclosedContracts: DisclosedContracts = DisclosedContracts(),
  ): Future[Unit] = {
    callCallbacksOnCompletionAndWaitForOffset(
      client
        .submitAndWait(
          workflowId = CNLedgerConnection.domainIdToWorkflowId(domainId),
          applicationId = applicationId,
          commandId = commandId.commandIdForSubmission,
          deduplicationConfig = DedupOffset(
            offset = deduplicationOffset
          ),
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          disclosedContracts = disclosedContracts assertOnDomain domainId,
        )
    )(offset => (offset, ()))
  }

  def submitCommandsTransaction(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      commandId: CommandId,
      deduplicationOffset: String,
      domainId: DomainId,
      disclosedContracts: DisclosedContracts = DisclosedContracts(),
  ): Future[Transaction] = {
    callCallbacksOnCompletionAndWaitForOffset(
      client
        .submitAndWaitForTransaction(
          workflowId = CNLedgerConnection.domainIdToWorkflowId(domainId),
          applicationId = applicationId,
          commandId = commandId.commandIdForSubmission,
          deduplicationConfig = DedupOffset(
            offset = deduplicationOffset
          ),
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          disclosedContracts = disclosedContracts assertOnDomain domainId,
        )
    )(tx => (tx.getOffset, tx))
  }

  def submitWithResultNoDedup[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      domainId: DomainId,
      disclosedContracts: DisclosedContracts = DisclosedContracts(),
  ): Future[T] =
    submitWithResultAndOffsetNoDedup(actAs, readAs, update, domainId, disclosedContracts).map(
      _._2
    )

  def submitWithResultAndOffsetNoDedup[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      domainId: DomainId,
      disclosedContracts: DisclosedContracts = DisclosedContracts(),
  ): Future[(String, T)] =
    doSubmitWithResultAndOffset(
      actAs,
      readAs,
      update,
      uniqueId,
      NoDedup,
      domainId,
      disclosedContracts,
    )

  def submitWithResult[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      commandId: CommandId,
      deduplicationConfig: DedupConfig,
      domainId: DomainId,
      disclosedContracts: DisclosedContracts = DisclosedContracts(),
  ): Future[T] =
    doSubmitWithResultAndOffset(
      actAs,
      readAs,
      update,
      commandId.commandIdForSubmission,
      deduplicationConfig,
      domainId,
      disclosedContracts,
    )
      .map(_._2)

  protected def doSubmitWithResultAndOffset[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      commandIdForSubmission: String,
      dedup: DedupConfig,
      domainId: DomainId,
      disclosedContracts: DisclosedContracts,
  ): Future[(String, T)] = {
    for {
      tree <- callCallbacksOnCompletionAndWaitForOffset(
        client
          .submitAndWaitForTransactionTree(
            workflowId = CNLedgerConnection.domainIdToWorkflowId(domainId),
            applicationId = applicationId,
            commandId = commandIdForSubmission,
            actAs = actAs.map(_.toProtoPrimitive),
            readAs = readAs.map(_.toProtoPrimitive),
            commands = update.commands.asScala.toSeq,
            deduplicationConfig = dedup,
            disclosedContracts = disclosedContracts assertOnDomain domainId,
          )
      )(tx => (tx.getOffset, tx))
    } yield (
      tree.getOffset,
      decodeExerciseResult(
        update,
        tree,
      ),
    )
  }

  def activeContracts(
      filter: IngestionFilter,
      offset: LedgerOffset.Absolute,
  ): Future[(Seq[ActiveContract], Seq[InFlightTransferOutEvent])] = {
    val activeContractsRequest = client.activeContracts(
      multidomain.GetActiveContractsRequest(
        validAtOffset = offset.getOffset,
        filter = Some(filter.toTransactionFilterAllContractsScala),
      )
    )
    for {
      responseSequence <- activeContractsRequest runWith Sink.seq
      contractStateComponents = responseSequence
        .flatMap(_.contractStateComponents)
        .map(_.contractStateComponent)
      active = contractStateComponents.collect {
        case multidomain.ContractStateComponent.ContractStateComponent.ActiveContract(contract) =>
          ActiveContract.fromProto(contract)
      }
      inFlight = contractStateComponents.collect {
        case multidomain.ContractStateComponent.ContractStateComponent
              .IncompleteTransferredOut(ev) =>
          InFlightTransferOutEvent.fromProto(ev)
      }
    } yield (active, inFlight)
  }

  def getConnectedDomains(party: PartyId): Future[Map[DomainAlias, DomainId]] =
    client.getConnectedDomains(party)

  def updates(
      beginOffset: LedgerOffset,
      party: PartyId,
  ): Source[LedgerClient.GetTreeUpdatesResponse, NotUsed] =
    client
      .updates(LedgerClient.GetUpdatesRequest(beginOffset, None, party))

  def tryGetTransactionTreeByEventId(
      parties: Seq[PartyId],
      id: String,
  ): Future[TransactionTree] =
    client.tryGetTransactionTreeByEventId(parties.map(_.toProtoPrimitive), id)

  def getOptionalPrimaryParty(user: String): Future[Option[PartyId]] = {
    for {
      user <- client
        .getUser(user)
        .map(Some(_))
      partyId = user.map(u =>
        PartyId.tryFromProtoPrimitive(
          u.getPrimaryParty.toScala
            .getOrElse(sys.error(s"user $user was allocated without primary party"))
        )
      )
    } yield partyId
  }

  def getPrimaryParty(user: String): Future[PartyId] = {
    for {
      partyIdO <- getOptionalPrimaryParty(user)
      partyId = partyIdO.getOrElse(
        sys.error(s"Unable to find party for user $user")
      )
    } yield partyId
  }

  def allocatePartyViaLedgerApi(
      hint: Option[String],
      displayName: Option[String],
  ): Future[PartyId] =
    client.allocateParty(hint, displayName).map(PartyId.tryFromProtoPrimitive)

  private def createPartyAndUser(
      user: String,
      userRights: Seq[User.Right],
  ): Future[PartyId] =
    for {
      party <- allocatePartyViaLedgerApi(
        Some(sanitizeUserIdToPartyString(user)),
        Some(user),
      )
      partyId <- createUserWithPrimaryParty(user, party, userRights)
    } yield partyId

  def createUserWithPrimaryParty(
      user: String,
      party: PartyId,
      userRights: Seq[User.Right],
  ): Future[PartyId] = {
    val userId = com.daml.lf.data.Ref.UserId.assertFromString(user)
    val userLf = new User(userId, party.toLf)
    for {
      user <- client
        .getOrCreateUser(userLf, new User.Right.CanActAs(party.toLf) +: userRights)
      partyId =
        PartyId.tryFromProtoPrimitive(
          user.getPrimaryParty.toScala
            .getOrElse(sys.error(s"user $user was allocated without primary party"))
        )
    } yield partyId
  }

  // TODO(tech-debt): Factor out user/party allocation and make it robust (current implementation is racy)
  def getOrAllocateParty(
      username: String,
      userRights: Seq[User.Right] = Seq.empty,
  ): Future[PartyId] = {
    for {
      existingPartyId <- getOptionalPrimaryParty(username).recover {
        case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
          None
      }
      partyId <- existingPartyId.fold[Future[PartyId]](
        createPartyAndUser(username, userRights)
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

  private def uploadDarFileInternal(packageId: String, path: String, darFile: => ByteString)(
      implicit traceContext: TraceContext
  ): Future[Unit] = {
    for {
      known <- client.listPackages()
      _ <- {
        if (known.contains(packageId)) {
          logger.debug(
            s"Skipping upload of dar file $path with package-id $packageId, as a package with that id has already been uploaded."
          )
          Future.successful(())
        } else {
          logger.debug(s"Uploading dar file $path with package-id ${packageId}")
          client.uploadDarFile(darFile)
        }
      }
      // TODO(tech-debt): The ledger API does not block until the package is vetted.
      //  Need to wait a bit, or use the Canton admin API to upload the package (that one does block).
      _ = Threading.sleep(500)
      _ = logger.info(
        s"Dar file $path with package-id $packageId should now be uploaded"
      )
    } yield ()
  }

  def uploadDarFiles(pkgs: Seq[UploadablePackage])(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    // TODO(#5141): allow limit parallel upload once Canton deals with concurrent uploads
    pkgs.foldLeft(Future.unit)((previous, dar) => previous.flatMap(_ => uploadDarFile(dar)))

  def uploadDarFile(
      pkg: UploadablePackage
  )(implicit traceContext: TraceContext): Future[Unit] =
    uploadDarFileInternal(
      pkg.packageId,
      pkg.resourcePath,
      ByteString.readFrom(pkg.inputStream()),
    )

  def uploadDarFile(
      path: Path
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      darFile <- Future {
        ByteString.readFrom(Files.newInputStream(path))
      }
      hash = DarParser.assertReadArchiveFromFile(path.toFile).main.getHash
      _ <- uploadDarFileInternal(hash, path.toString, darFile)
    } yield ()

  def submitTransferAndWaitNoDedup(
      submitter: PartyId,
      command: LedgerClient.TransferCommand,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val commandId = UUID.randomUUID().toString()
    logger.debug(s"transfer $commandId is for $command")

    val (ks, completion) = cancelIfFailed(
      client
        .completions(applicationId, Seq(submitter), begin = None)
        .wireTap(csr => logger.trace(s"completions while awaiting transfer $commandId: $csr"))
    )(awaitCompletion(applicationId = applicationId, commandId = commandId))(
      // We call the callbacks for handling stale contract errors here, but wait for the offset
      // ingestion at which the completion is reported.
      callCallbacksOnCompletionNoWaitForOffset(
        client
          .submitTransfer(
            applicationId,
            commandId,
            submissionId = commandId,
            submitter,
            command,
          )
          .andThen { _ =>
            // TODO(#2864) Consider removing this log once we no longer needs this
            // for synchronization.
            logger.info(
              s"Submitted transfer to ledger, waiting for completion: commandId: $commandId"
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
          s"shutting down while awaiting completion of transfer $commandId; pretending a completion arrived"
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
    import scala.concurrent.duration.*
    // TODO(#5232) Bump this back to timeouts.network.asFiniteApproximation
    val howLongToWait = 10.seconds
    Flow[LedgerClient.CompletionStreamResponse]
      .completionTimeout(howLongToWait)
      .mapError { case te: concurrent.TimeoutException =>
        DEADLINE_EXCEEDED
          .withCause(te)
          .augmentDescription(s"timeout while awaiting completion of transfer $commandId")
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
                s"participant stopped while awaiting completion of transfer $commandId"
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

/** Subscription for reading the ledger */
class CNLedgerSubscription[S](
    source: Source[S, NotUsed],
    mapOperator: Flow[S, ?, ?],
    retryProvider: RetryProvider,
    override protected val timeouts: ProcessingTimeout,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends FlagCloseableAsync
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

object CNLedgerConnection {

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

  def uniqueId: String = UUID.randomUUID.toString
}
