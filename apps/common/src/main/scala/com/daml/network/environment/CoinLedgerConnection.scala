package com.daml.network.environment

import akka.actor.ActorSystem
import akka.stream.{KillSwitch, KillSwitches, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.{Done, NotUsed}
import com.daml.error.utils.ErrorDetails
import com.daml.error.utils.ErrorDetails.ResourceInfoDetail
import com.daml.ledger.api.v1.CommandsOuterClass
import com.daml.ledger.javaapi.data.codegen.{Contract, ContractId, Created, Exercised, Update}
import com.daml.ledger.javaapi.data.{
  Command,
  CreatedEvent,
  ExercisedEvent,
  Identifier,
  LedgerOffset,
  Template,
  Transaction,
  TransactionTree,
  User,
}
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.TreeUpdate
import com.daml.network.store.AcsStore.IngestionFilter
import com.daml.network.util.Contract.Companion.Template as TemplateCompanion
import com.daml.network.util.CreatedEventImplicits.*
import com.daml.network.util.{Trees, UploadablePackage}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.error.CantonErrorResource
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseable,
  FlagCloseableAsync,
  RunOnShutdown,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.messages.LocalReject.ConsistencyRejections.InactiveContracts
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
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success, Try}

class CoinLedgerConnection(
    client: LedgerClient,
    applicationId: String,
    protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
    retryProvider: CoinRetries,
    inactiveContractCallbacks: AtomicReference[Seq[String => Unit]],
)(implicit as: ActorSystem, ec: ExecutionContextExecutor)
    extends FlagCloseable
    with NamedLogging {

  logger.debug(s"Created connection with applicationId=$applicationId")(
    TraceContext.empty
  )

  override def onClosed(): Unit = {}

  import CoinLedgerConnection.*

  private def callCallbacksOnCompletion[T](result: Future[T]): Future[T] = {
    import TraceContext.Implicits.Empty.*
    def callCallbacksInternal(result: Try[?]): Unit =
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
    result
  }

  def submitCommandsNoDedup(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      domainId: DomainId,
      disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq(),
  ): Future[Unit] = {
    callCallbacksOnCompletion(
      client
        .submitAndWait(
          workflowId = CoinLedgerConnection.domainIdToWorkflowId(domainId),
          applicationId = applicationId,
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          commandId = uniqueId,
          deduplicationConfig = NoDedup,
          disclosedContracts = disclosedContracts,
        )
    )
  }

  def submitCommandsNoDedupTransaction(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      domainId: DomainId,
      disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq(),
  ): Future[Transaction] = {
    callCallbacksOnCompletion(
      client
        .submitAndWaitForTransaction(
          workflowId = CoinLedgerConnection.domainIdToWorkflowId(domainId),
          applicationId = applicationId,
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          commandId = uniqueId,
          deduplicationConfig = NoDedup,
          disclosedContracts = disclosedContracts,
        )
    )
  }

  def submitCommands(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      commandId: CommandId,
      deduplicationOffset: String,
      domainId: DomainId,
      disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq(),
  ): Future[Unit] = {
    callCallbacksOnCompletion(
      client
        .submitAndWait(
          workflowId = CoinLedgerConnection.domainIdToWorkflowId(domainId),
          applicationId = applicationId,
          commandId = commandId.commandIdForSubmission,
          deduplicationConfig = DedupOffset(
            offset = deduplicationOffset
          ),
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          disclosedContracts = disclosedContracts,
        )
    )
  }

  def submitWithResultNoDedup[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      domainId: DomainId,
      disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq(),
  ): Future[T] =
    submitWithResultAndOffsetNoDedup(actAs, readAs, update, domainId, disclosedContracts).map(
      _._2
    )

  def submitWithResultAndOffsetNoDedup[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      domainId: DomainId,
      disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq(),
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
      disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq(),
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
      disclosedContracts: Seq[CommandsOuterClass.DisclosedContract],
  ): Future[(String, T)] = {
    for {
      tree <- callCallbacksOnCompletion(
        client
          .submitAndWaitForTransactionTree(
            workflowId = CoinLedgerConnection.domainIdToWorkflowId(domainId),
            applicationId = applicationId,
            commandId = commandIdForSubmission,
            actAs = actAs.map(_.toProtoPrimitive),
            readAs = readAs.map(_.toProtoPrimitive),
            commands = update.commands.asScala.toSeq,
            deduplicationConfig = dedup,
            disclosedContracts = disclosedContracts,
          )
      )
    } yield (
      tree.getOffset,
      decodeExerciseResult(
        update,
        tree,
      ),
    )
  }

  def activeContractsWithOffset(
      domain: DomainId,
      filter: IngestionFilter,
  ): Future[(Seq[CreatedEvent], LedgerOffset)] =
    client.ledgerEnd(domain).flatMap {
      case lb: LedgerOffset.LedgerBegin => Future.successful((Seq.empty, lb))
      case simulatedAcsEnd =>
        val req = LedgerClient.GetUpdatesRequest(
          LedgerOffset.LedgerBegin.getInstance,
          Some(simulatedAcsEnd),
          filter.primaryParty,
          domain,
        )
        val filterCreates = interpretCreateFilter(filter)
        val mapCe = client
          .updates(req)
          .via(treesAsTransactions)
          .runWith(Sink.fold(Map.empty[String, CreatedEvent]) { (cidCe, tx) =>
            val (creates, archives) = tx.events.partitionMap {
              case AcsEvent.Created(ce) => Left((ce.getContractId, ce))
              case AcsEvent.Archived(cid) => Right(cid)
            }
            val tpIdFilteredCreates = creates.view filter { case (_, ce) => filterCreates(ce) }
            cidCe ++ tpIdFilteredCreates -- archives
          })
        mapCe.map(m => (m.values.toSeq, simulatedAcsEnd))
    }

  private def interpretCreateFilter(filter: IngestionFilter): CreatedEvent => Boolean = {
    val IngestionFilter(primaryParty, templateIds, interfaceIds) = filter
    ce =>
      ce.hasStakeholder(primaryParty) &&
        (templateIds(ce.getTemplateId) ||
          Seq(ce.getInterfaceViews, ce.getFailedInterfaceViews).exists(
            _.keySet.asScala exists interfaceIds
          ))
  }

  def activeContractsWithOffset[TCid <: ContractId[
    T
  ], T <: Template](
      domain: DomainId,
      party: PartyId,
      companion: TemplateCompanion[TCid, T],
  ): Future[(Seq[Contract[TCid, T]], LedgerOffset)] =
    activeContractsWithOffset(
      domain,
      CoinLedgerConnection.transactionFilterByParty(party, companion.TEMPLATE_ID),
    ).map { case (events, off) =>
      (events.map(companion.fromCreatedEvent(_)), off)
    }

  def activeContracts(
      domain: DomainId,
      filter: IngestionFilter,
  ): Future[Seq[CreatedEvent]] =
    activeContractsWithOffset(domain, filter).map(_._1)

  def activeContracts[TCid <: ContractId[T], T <: Template](
      domain: DomainId,
      party: PartyId,
      companion: TemplateCompanion[TCid, T],
  ): Future[Seq[Contract[TCid, T]]] =
    activeContractsWithOffset(domain, party, companion).map(_._1)

  // TODO (#2706)
  // This is a hacked up wrapper that transforms transaction trees into flat transactions.
  // This is required because the update service initially only exposes transaction trees.
  //
  // It is limited in a quite a few ways:
  // 1. It only does party filtering.
  // 2. It does not filter out transient contracts.
  // 3. It does not filter out creates and archives the
  //    subscribing parties are witnesses but not stakeholders on.
  // 4. It does not support interfaces.
  //
  // All those limitations are acceptable perform additional filters
  // on contracts which include those filters and for the PoC
  // we can assume that we know the templates that implement a given interface.
  private def subscription(
      clientName: String,
      baseLoggerFactory: NamedLoggerFactory,
      beginOffset: LedgerOffset,
      party: PartyId,
      domain: DomainId,
  )(mapOperator: Flow[TreeUpdate, Any, _]): CoinLedgerSubscription[?] = {
    new CoinLedgerSubscription(
      client.updates(
        LedgerClient.GetUpdatesRequest(beginOffset, None, party, domain)
      ),
      Flow[LedgerClient.GetTreeUpdatesResponse].mapConcat(_.updates).via(mapOperator),
      timeouts,
      baseLoggerFactory.append("subsClient", clientName),
    )
  }

  private def treesAsTransactions
      : Flow[LedgerClient.GetTreeUpdatesResponse, AcsTransaction, NotUsed] =
    Flow[LedgerClient.GetTreeUpdatesResponse].mapConcat(_.updates).map {
      case LedgerClient.GetTreeUpdatesResponse.TransactionTreeUpdate(tree) => flattenTree(tree)
      case LedgerClient.GetTreeUpdatesResponse.TransferUpdate(transfer) =>
        val ev = transfer.event match {
          case in: LedgerClient.GetTreeUpdatesResponse.TransferEvent.In =>
            AcsEvent.Created(in.createdEvent)
          case out: LedgerClient.GetTreeUpdatesResponse.TransferEvent.Out =>
            AcsEvent.Archived(
              out.contractId.contractId
            )
        }
        AcsTransaction(
          Seq(ev)
        )
    }

  private def flattenTree(tree: TransactionTree): AcsTransaction = {
    val events: mutable.Buffer[AcsEvent] = mutable.ListBuffer()
    Trees.traverseTree(
      tree,
      onCreate = (ev, _) => {
        events.append(AcsEvent.Created(ev))
      },
      onExercise = (ev, _) => {
        if (ev.isConsuming) {
          events.append(
            AcsEvent.Archived(
              ev.getContractId
            )
          )
        }
      },
    )
    AcsTransaction(
      events.toSeq
    )
  }

  // TODO (#2706)
  // See `subscribe` for limitations of this method.
  def subscribeAsync(
      clientName: String,
      baseLoggerFactory: NamedLoggerFactory,
      beginOffset: LedgerOffset,
      filter: PartyId,
      domain: DomainId,
  )(f: TreeUpdate => Future[Unit]): CoinLedgerSubscription[?] =
    subscription(clientName, baseLoggerFactory, beginOffset, filter, domain)({
      Flow[TreeUpdate].mapAsync(1)(f)
    })

  def tryGetTransactionTreeById(
      parties: Seq[PartyId],
      id: String,
  ): Future[TransactionTree] =
    client.tryGetTransactionTreeById(parties.map(_.toProtoPrimitive), id)

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

  def createPartyAndUser(
      user: String,
      userRights: Seq[User.Right],
  ): Future[PartyId] = {
    for {
      party <- allocatePartyViaLedgerApi(Some(sanitizeUserIdToLedgerString(user)), Some(user))
      partyId <- createUserWithPrimaryParty(user, party, userRights)
    } yield partyId
  }

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

  private def uploadDarFileInternal(packageId: String, darFile: => ByteString)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      known <- client.listPackages()
      _ <- {
        if (known.contains(packageId)) {
          logger.debug(s"Package of dar $packageId already exists")
          Future.successful(())
        } else {
          logger.debug(s"Uploading dar file ${packageId}")
          client.uploadDarFile(darFile)
        }
      }
    } yield ()
  }

  def uploadDarFile(
      pkg: UploadablePackage
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      _ <- uploadDarFileInternal(
        pkg.packageId,
        ByteString.readFrom(pkg.inputStream()),
      )
      // TODO(tech-debt): The ledger API does not block until the package is vetted.
      //  Need to wait a bit, or use the Canton admin API to upload the package (that one does block).
      _ = Threading.sleep(500)
      _ = logger.info(s"Package ${pkg.packageId} is uploaded")
    } yield ()
  }

  def uploadDarFile(
      path: Path
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      darFile <- Future {
        ByteString.readFrom(Files.newInputStream(path))
      }
      // TODO(tech-debt) Consider if we want to be clever
      // and only upload if it has not already been uploaded.
      _ <- client.uploadDarFile(darFile)
      // TODO(tech-debt): The ledger API does not block until the package is vetted.
      //  Need to wait a bit, or use the Canton admin API to upload the package (that one does block).
      _ = Threading.sleep(500)
      _ = logger.info(s"DAR $path is uploaded")
    } yield ()
  }

  def submitTransferAndWaitNoDedup(
      submitter: PartyId,
      command: LedgerClient.TransferCommand,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val commandId = UUID.randomUUID().toString()
    val listenDomain = command match {
      case in: LedgerClient.TransferCommand.In => in.target
      case out: LedgerClient.TransferCommand.Out => out.source
    }
    logger.debug(s"transfer $commandId is for $command")

    val (ks, completion) = cancelIfFailed(
      client
        .completions(applicationId, Seq(submitter), begin = None, listenDomain)
        .wireTap(csr => logger.trace(s"completions while awaiting transfer $commandId: $csr"))
    )(awaitCompletion(applicationId = applicationId, commandId = commandId))(
      callCallbacksOnCompletion(
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

    retryProvider.runOnShutdown(new RunOnShutdown {
      override def name: String = "close-completions-stream"
      override def done: Boolean = completion.isCompleted
      override def run(): Unit = ks.shutdown()
    })

    completion.map(_ => ())
  }

  // simulate the completion check of command service; future only yields
  // successfully if the completion was OK
  private[this] def awaitCompletion(
      applicationId: String,
      commandId: String,
  )(implicit
      traceContext: TraceContext
  ): Sink[LedgerClient.CompletionStreamResponse, Future[LedgerClient.Completion]] = {
    import io.grpc.Status.{DEADLINE_EXCEEDED, UNAVAILABLE}
    val howLongToWait = timeouts.network.asFiniteApproximation
    Flow[LedgerClient.CompletionStreamResponse]
      .completionTimeout(howLongToWait)
      .mapError { case te: concurrent.TimeoutException =>
        DEADLINE_EXCEEDED
          .withCause(te)
          .augmentDescription(s"timeout while awaiting completion of transfer $commandId")
          .asRuntimeException()
      }
      .collect(
        Function unlift { csr =>
          // TODO(tech-debt) don't parse completions that don't match
          csr.completions find (_.matchesSubmission(applicationId, commandId, commandId))
        }
      )
      .take(1)
      .wireTap(cpl => logger.debug(s"selected completion for $commandId: $cpl"))
      .toMat(
        Sink
          .headOption[LedgerClient.Completion]
          .mapMaterializedValue(_ map (_ map { completion =>
            if (completion.status.isOk) completion
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
class CoinLedgerSubscription[S](
    source: Source[S, NotUsed],
    mapOperator: Flow[S, ?, ?],
    override protected val timeouts: ProcessingTimeout,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends FlagCloseableAsync
    with NamedLogging {

  import TraceContext.Implicits.Empty.*

  private val (killSwitch, completed_) = AkkaUtil.runSupervised(
    logger.error("Fatally failed to handle transaction", _),
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

object CoinLedgerConnection {

  // TODO(#2699) Remove this once we have a proper ACS endpoint

  /** This represents an update to the ACS that we got through the update stream.
    * We also represent transfer in/out as AcsTransactions here not just
    * regular Daml transactions.
    */
  final case class AcsTransaction(
      events: Seq[AcsEvent]
  )

  sealed abstract class AcsEvent extends Product with Serializable

  object AcsEvent {
    final case class Created(event: CreatedEvent) extends AcsEvent

    final case class Archived(contractId: String) extends AcsEvent
  }

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

  /** In a number of places we want to use a user id in a place where a `LedgerString` is expected, e.g.,
    * in party id hints and in workflow ids. However, the allowed set of characters is slightly different so
    * this function can be used to perform the necessary escaping.g
    */
  def sanitizeUserIdToLedgerString(userId: String): String = {
    val (processed, invalidCharDetected) = userId.foldLeft(("", false))((res, currentChar) => {
      if ("[^\\w-_:]".r matches (s"$currentChar")) {
        (res._1 + "_", true)
      } else {
        (res._1 + currentChar, res._2)
      }
    })

    if (invalidCharDetected) {
      // append a UUID if we had to rewrite the user id
      // because there's a chance it could now conflict with an existing party
      s"${processed}-${UUID.randomUUID.toString}"
    } else {
      processed
    }
  }

  def transactionFilterByParty(partyId: PartyId, templateId: Identifier): IngestionFilter =
    IngestionFilter(partyId, templateIds = Set(templateId), interfaceIds = Set.empty)

  def uniqueId: String = UUID.randomUUID.toString
}
