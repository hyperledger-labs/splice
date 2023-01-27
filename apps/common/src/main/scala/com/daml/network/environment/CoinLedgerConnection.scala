package com.daml.network.environment

import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.TreeUpdate
import akka.actor.ActorSystem
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.{Done, NotUsed}
import com.daml.ledger.javaapi.data.codegen.{
  Contract,
  ContractCompanion,
  ContractId,
  Created,
  Exercised,
  Update,
}
import com.daml.ledger.javaapi.data.{
  ArchivedEvent,
  Command,
  CreatedEvent,
  Event,
  ExercisedEvent,
  Identifier,
  LedgerOffset,
  Template,
  Transaction,
  TransactionTree,
  User,
}
import com.daml.network.store.AcsStore.IngestionFilter
import com.daml.network.util.{Trees, UploadablePackage}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil as DecodeUtil
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import com.digitalasset.canton.util.AkkaUtil
import com.google.protobuf.ByteString
import io.grpc.StatusRuntimeException

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success}

trait CoinLedgerSubmit extends FlagCloseableAsync {

  def submitCommands(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      commandId: CoinLedgerConnection.CommandId,
      deduplicationOffset: String,
      domainId: DomainId,
  )(implicit traceContext: TraceContext): Future[Transaction]

  // TODO(M3-60): review all uses of command submission w/o deduplication
  def submitCommandsNoDedup(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      domainId: DomainId,
  )(implicit traceContext: TraceContext): Future[Transaction]

  def submitWithResultNoDedup[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      domainId: DomainId,
  )(implicit traceContext: TraceContext): Future[T]

  def submitWithResultAndOffsetNoDedup[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      domainId: DomainId,
  )(implicit traceContext: TraceContext): Future[(String, T)]

  def submitWithResult[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      commandId: CoinLedgerConnection.CommandId,
      deduplicationConfig: DedupConfig,
      domainId: DomainId,
  )(implicit traceContext: TraceContext): Future[T]
}

trait CoinLedgerConnection extends CoinLedgerSubmit {
  def activeContractsWithOffset(
      domain: DomainId,
      filter: IngestionFilter,
  ): Future[(Seq[CreatedEvent], LedgerOffset)]

  def activeContractsWithOffset[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      domain: DomainId,
      party: PartyId,
      companion: ContractCompanion[TC, TCid, T],
  ): Future[(Seq[Contract[TCid, T]], LedgerOffset)]

  def activeContracts(
      domain: DomainId,
      filter: IngestionFilter,
  ): Future[Seq[CreatedEvent]]

  def activeContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      domain: DomainId,
      party: PartyId,
      companion: ContractCompanion[TC, TCid, T],
  ): Future[Seq[Contract[TCid, T]]]

  def subscribeAsync(
      subscriptionName: String,
      beginOffset: LedgerOffset,
      filter: PartyId,
      domain: DomainId,
  )(f: TreeUpdate => Future[Unit]): CoinLedgerSubscription

  def updateCreates[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      domainId: DomainId,
      filter: PartyId,
      companion: ContractCompanion[TC, TCid, T],
  ): Source[Contract[TCid, T], NotUsed]

  def updateTransferOuts(
      domainId: DomainId,
      filter: PartyId,
  ): Source[LedgerClient.GetTreeUpdatesResponse.TransferEvent.Out, NotUsed]

  def tryGetTransactionTreeById(parties: Seq[PartyId], id: String): Future[TransactionTree]

  def tryGetTransactionTreeByEventId(parties: Seq[PartyId], id: String): Future[TransactionTree]

  def getOptionalPrimaryParty(user: String): Future[Option[PartyId]]

  def getPrimaryParty(user: String): Future[PartyId]

  def allocatePartyViaLedgerApi(hint: Option[String], displayName: Option[String]): Future[PartyId]

  def createPartyAndUser(user: String, userRights: Seq[User.Right]): Future[PartyId]

  def getOrAllocateParty(
      username: String,
      userRights: Seq[User.Right] = Seq.empty,
  )(implicit traceContext: TraceContext): Future[PartyId]

  def getUserReadAs(username: String): Future[Set[PartyId]]

  def grantUserRights(
      user: String,
      actAsParties: Seq[PartyId],
      readAsParties: Seq[PartyId],
  ): Future[Unit]

  def revokeUserRights(
      user: String,
      actAsParties: Seq[PartyId],
      readAsParties: Seq[PartyId],
  ): Future[Unit]

  def listPackages()(implicit traceContext: TraceContext): Future[Set[String]]

  def uploadDarFile(pkg: UploadablePackage)(implicit traceContext: TraceContext): Future[Unit]

  def uploadDarFile(path: Path)(implicit traceContext: TraceContext): Future[Unit]

  def submitTransferNoDedup(
      submitter: PartyId,
      command: LedgerClient.TransferCommand,
  ): Future[Unit]
}

/** Subscription for reading the ledger */
trait CoinLedgerSubscription extends FlagCloseableAsync with NamedLogging {
  val completed: Future[Done]
}

object CoinLedgerConnection {

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

  def apply(
      coinLedgerClient: CoinLedgerClient,
      loggerFactoryForCoinLedgerConnectionOverride: NamedLoggerFactory,
      tracerProvider: TracerProvider,
  ): CoinLedgerConnection with NamedLogging =
    new CoinLedgerConnection with NamedLogging {
      protected val loggerFactory: NamedLoggerFactory = loggerFactoryForCoinLedgerConnectionOverride

      override protected def timeouts: ProcessingTimeout = coinLedgerClient.timeouts

      private def client = coinLedgerClient.client

      implicit private def as: ActorSystem = coinLedgerClient.actorSystem

      implicit private def ec: ExecutionContextExecutor = coinLedgerClient.executionContextExecutor

      def submitCommandsNoDedup(
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          commands: Seq[Command],
          domainId: DomainId,
      )(implicit traceContext: TraceContext): Future[Transaction] = {
        client.submitAndWaitForTransaction(
          workflowId = CoinLedgerConnection.domainIdToWorkflowId(domainId),
          applicationId = coinLedgerClient.applicationId,
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          commandId = uniqueId,
          deduplicationConfig = NoDedup,
        )
      }

      def submitCommands(
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          commands: Seq[Command],
          commandId: CommandId,
          deduplicationOffset: String,
          domainId: DomainId,
      )(implicit traceContext: TraceContext): Future[Transaction] = {
        client.submitAndWaitForTransaction(
          workflowId = CoinLedgerConnection.domainIdToWorkflowId(domainId),
          applicationId = coinLedgerClient.applicationId,
          commandId = commandId.commandIdForSubmission,
          deduplicationConfig = DedupOffset(
            offset = deduplicationOffset
          ),
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
        )
      }

      def submitWithResultNoDedup[T](
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          update: Update[T],
          domainId: DomainId,
      )(implicit traceContext: TraceContext): Future[T] =
        submitWithResultAndOffsetNoDedup(actAs, readAs, update, domainId).map(_._2)

      def submitWithResultAndOffsetNoDedup[T](
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          update: Update[T],
          domainId: DomainId,
      )(implicit traceContext: TraceContext): Future[(String, T)] =
        doSubmitWithResultAndOffset(actAs, readAs, update, uniqueId, NoDedup, domainId)

      def submitWithResult[T](
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          update: Update[T],
          commandId: CommandId,
          dedupConfig: DedupConfig,
          domainId: DomainId,
      )(implicit traceContext: TraceContext): Future[T] =
        doSubmitWithResultAndOffset(
          actAs,
          readAs,
          update,
          commandId.commandIdForSubmission,
          dedupConfig,
          domainId,
        )
          .map(_._2)

      def doSubmitWithResultAndOffset[T](
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          update: Update[T],
          commandIdForSubmission: String,
          dedup: DedupConfig,
          domainId: DomainId,
      ): Future[(String, T)] = {
        for {
          tree <- client.submitAndWaitForTransactionTree(
            workflowId = CoinLedgerConnection.domainIdToWorkflowId(domainId),
            applicationId = coinLedgerClient.applicationId,
            commandId = commandIdForSubmission,
            actAs = actAs.map(_.toProtoPrimitive),
            readAs = readAs.map(_.toProtoPrimitive),
            commands = update.commands.asScala.toSeq,
            deduplicationConfig = dedup,
          )
        } yield (
          tree.getOffset,
          decodeExerciseResult(
            update,
            tree,
          ),
        )
      }

      override def activeContractsWithOffset(
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
                val (creates, archives) = tx.getEvents.asScala.partitionMap {
                  case ce: CreatedEvent => Left((ce.getContractId, ce))
                  case ae: ArchivedEvent => Right(ae.getContractId)
                  case e: Event =>
                    sys.error(s"${e.getClass.getSimpleName} was not a create or archive")
                }
                val tpIdFilteredCreates = creates.view filter { case (_, ce) => filterCreates(ce) }
                cidCe ++ tpIdFilteredCreates -- archives
              })
            mapCe.map(m => (m.values.toSeq, simulatedAcsEnd))
        }

      private def interpretCreateFilter(filter: IngestionFilter): CreatedEvent => Boolean = {
        val IngestionFilter(primaryParty @ _, templateIds, interfaceIds) = filter
        ce =>
          templateIds(ce.getTemplateId) ||
            Seq(ce.getInterfaceViews, ce.getFailedInterfaceViews).exists(
              _.keySet.asScala exists interfaceIds
            )
      }

      override def activeContractsWithOffset[TC <: Contract[TCid, T], TCid <: ContractId[
        T
      ], T <: Template](
          domain: DomainId,
          party: PartyId,
          companion: ContractCompanion[TC, TCid, T],
      ): Future[(Seq[Contract[TCid, T]], LedgerOffset)] =
        activeContractsWithOffset(
          domain,
          CoinLedgerConnection.transactionFilterByParty(party, companion.TEMPLATE_ID),
        ).map { case (events, off) =>
          (events.map(companion.fromCreatedEvent(_)), off)
        }

      override def activeContracts(
          domain: DomainId,
          filter: IngestionFilter,
      ): Future[Seq[CreatedEvent]] =
        activeContractsWithOffset(domain, filter).map(_._1)

      override def activeContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
          domain: DomainId,
          party: PartyId,
          companion: ContractCompanion[TC, TCid, T],
      ): Future[Seq[Contract[TCid, T]]] =
        activeContractsWithOffset(domain, party, companion).map(_._1)

      // TODO (M3-18)
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
          subscriptionName: String,
          beginOffset: LedgerOffset,
          party: PartyId,
          domain: DomainId,
      )(mapOperator: Flow[TreeUpdate, Any, _]): CoinLedgerSubscription = {
        makeSubscription(
          client.updates(
            LedgerClient.GetUpdatesRequest(beginOffset, None, party, domain)
          ),
          Flow[LedgerClient.GetTreeUpdatesResponse].mapConcat(_.updates).via(mapOperator),
          subscriptionName,
        )
      }

      private def treesAsTransactions
          : Flow[LedgerClient.GetTreeUpdatesResponse, Transaction, NotUsed] =
        treesFromResponse.map(flattenTree)

      private def treesFromResponse
          : Flow[LedgerClient.GetTreeUpdatesResponse, TransactionTree, NotUsed] =
        Flow[LedgerClient.GetTreeUpdatesResponse]
          .mapConcat(
            _.discardTransfers // TODO (M3-18) do not simply discard transfers
          )

      private def flattenTree(tree: TransactionTree): Transaction = {
        val events: mutable.Buffer[Event] = mutable.ListBuffer()
        Trees.traverseTree(
          tree,
          onCreate = (ev, _) => {
            events.append(ev)
          },
          onExercise = (ev, _) => {
            if (ev.isConsuming) {
              events.append(
                new ArchivedEvent(
                  ev.getWitnessParties,
                  ev.getEventId,
                  ev.getTemplateId,
                  ev.getContractId,
                )
              )
            }
          },
        )
        new Transaction(
          tree.getTransactionId(),
          tree.getCommandId(),
          tree.getWorkflowId(),
          tree.getEffectiveAt(),
          events.toSeq.asJava,
          tree.getOffset(),
        )
      }

      // TODO (M3-18)
      // See `subscribe` for limitations of this method.
      override def subscribeAsync(
          subscriptionName: String,
          beginOffset: LedgerOffset,
          filter: PartyId,
          domain: DomainId,
      )(f: TreeUpdate => Future[Unit]): CoinLedgerSubscription =
        subscription(subscriptionName, beginOffset, filter, domain)({
          Flow[TreeUpdate].mapAsync(1)(f)
        })

      def updateCreates[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
          domainId: DomainId,
          party: PartyId,
          companion: ContractCompanion[TC, TCid, T],
      ): Source[Contract[TCid, T], NotUsed] = {
        coinLedgerClient.client
          .updates(
            LedgerClient.GetUpdatesRequest(
              begin = LedgerOffset.LedgerBegin.getInstance,
              end = None,
              party = party,
              domainId = domainId,
            )
          )
          .mapConcat(_.discardTransfers.flatMap(DecodeUtil.decodeAllCreatedTree(companion)(_)))
      }

      def updateTransferOuts(
          domainId: DomainId,
          party: PartyId,
      ): Source[LedgerClient.GetTreeUpdatesResponse.TransferEvent.Out, NotUsed] = {
        import LedgerClient.GetTreeUpdatesResponse.{Transfer, TransferEvent}
        coinLedgerClient.client
          .updates(
            LedgerClient.GetUpdatesRequest(
              // fixme
              begin = LedgerOffset.LedgerBegin.getInstance,
              end = None,
              party = party,
              domainId = domainId,
            )
          )
          .mapConcat { response =>
            response.updates.collect { case Transfer(_, _, out: TransferEvent.Out) =>
              out
            }
          }
      }

      private def makeSubscription[S, T](
          source: Source[S, NotUsed],
          mapOperator: Flow[S, T, _],
          subscriptionName: String,
      ): CoinLedgerSubscription =
        new CoinLedgerSubscription {
          override protected def timeouts: ProcessingTimeout = coinLedgerClient.timeouts

          import TraceContext.Implicits.Empty.*

          val (killSwitch, completed) = AkkaUtil.runSupervised(
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
          override val loggerFactory: NamedLoggerFactory =
            if (subscriptionName.isEmpty)
              loggerFactoryForCoinLedgerConnectionOverride
            else
              loggerFactoryForCoinLedgerConnectionOverride.append("client", subscriptionName)

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
                coinLedgerClient.timeouts.shutdownShort.unwrap,
              ),
            )
          }
        }

      override def tryGetTransactionTreeById(
          parties: Seq[PartyId],
          id: String,
      ): Future[TransactionTree] =
        client.tryGetTransactionTreeById(parties.map(_.toProtoPrimitive), id)

      override def tryGetTransactionTreeByEventId(
          parties: Seq[PartyId],
          id: String,
      ): Future[TransactionTree] =
        client.tryGetTransactionTreeByEventId(parties.map(_.toProtoPrimitive), id)

      override def getOptionalPrimaryParty(user: String): Future[Option[PartyId]] = {
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

      override def getPrimaryParty(user: String): Future[PartyId] = {
        for {
          partyIdO <- getOptionalPrimaryParty(user)
          partyId = partyIdO.getOrElse(
            sys.error(s"Unable to find party for user $user")
          )
        } yield partyId
      }

      override def allocatePartyViaLedgerApi(
          hint: Option[String],
          displayName: Option[String],
      ): Future[PartyId] =
        client.allocateParty(hint, displayName).map(PartyId.tryFromProtoPrimitive)

      override def createPartyAndUser(
          user: String,
          userRights: Seq[User.Right],
      ): Future[PartyId] = {
        for {
          party <- allocatePartyViaLedgerApi(Some(sanitizeUserIdToLedgerString(user)), Some(user))
          userId = com.daml.lf.data.Ref.UserId.assertFromString(user)
          userLf = new User(userId, party.toLf)

          user <- client
            .createUser(userLf, new User.Right.CanActAs(party.toLf) +: userRights)
          partyId =
            PartyId.tryFromProtoPrimitive(
              user.getPrimaryParty.toScala
                .getOrElse(sys.error(s"user $user was allocated without primary party"))
            )
        } yield partyId
      }

      // TODO(tech-debt): Factor out user/party allocation and make it robust (current implementation is racy)
      override def getOrAllocateParty(
          username: String,
          userRights: Seq[User.Right] = Seq.empty,
      )(implicit traceContext: TraceContext): Future[PartyId] = {
        for {
          existingPartyId <- getOptionalPrimaryParty(username).recover {
            case e: StatusRuntimeException
                if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
              None
          }
          partyId <- existingPartyId.fold[Future[PartyId]](
            createPartyAndUser(username, userRights)
          )(
            Future.successful
          )
        } yield partyId
      }

      override def getUserReadAs(
          username: String
      ): Future[Set[PartyId]] = {
        val userId = com.daml.lf.data.Ref.UserId.assertFromString(username)
        for {
          userRights <- client.listUserRights(userId)
        } yield userRights.collect { case readAs: User.Right.CanReadAs =>
          PartyId.tryFromProtoPrimitive(readAs.party)
        }.toSet
      }

      override def grantUserRights(
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

      override def revokeUserRights(
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

      override def listPackages()(implicit traceContext: TraceContext): Future[Set[String]] =
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

      override def uploadDarFile(
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

      override def uploadDarFile(
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

      override def submitTransferNoDedup(
          submitter: PartyId,
          command: LedgerClient.TransferCommand,
      ): Future[Unit] = {
        val commandId = UUID.randomUUID().toString()
        client.submitTransfer(coinLedgerClient.applicationId, commandId, submitter, command)
      }

      override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = List[AsyncOrSyncCloseable]()
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

  private def transactionFilterByParty(partyId: PartyId, templateId: Identifier): IngestionFilter =
    IngestionFilter(partyId, templateIds = Set(templateId), interfaceIds = Set.empty)

  def uniqueId: String = UUID.randomUUID.toString
}
