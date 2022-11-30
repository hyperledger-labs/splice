package com.daml.network.environment

import akka.actor.{ActorSystem, Cancellable}
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
  Command,
  CreatedEvent,
  ExercisedEvent,
  Filter,
  FiltersByParty,
  GetActiveContractsRequest,
  GetTransactionsRequest,
  GetTransactionsResponse,
  Identifier,
  InclusiveFilter,
  LedgerOffset,
  Template,
  Transaction,
  TransactionFilter,
  TransactionTree,
  User,
}
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import com.digitalasset.canton.util.AkkaUtil
import com.google.protobuf.ByteString
import com.google.protobuf.timestamp.Timestamp
import io.grpc.StatusRuntimeException

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.UUID
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
  )(implicit traceContext: TraceContext): Future[Transaction]

  // TODO(M3-60): review all uses of command submission w/o deduplication
  def submitCommandsNoDedup(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
  )(implicit traceContext: TraceContext): Future[Transaction]

  def submitWithResultNoDedup[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
  )(implicit traceContext: TraceContext): Future[T]

  def submitWithResultAndOffsetNoDedup[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
  )(implicit traceContext: TraceContext): Future[(String, T)]
}

trait CoinLedgerConnection extends CoinLedgerSubmit {
  def activeContractsWithOffset(
      filter: TransactionFilter
  ): Future[(Seq[CreatedEvent], LedgerOffset)]

  def activeContractsWithOffset[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      party: PartyId,
      companion: ContractCompanion[TC, TCid, T],
  ): Future[(Seq[Contract[TCid, T]], LedgerOffset)]

  def activeContracts(
      filter: TransactionFilter
  ): Future[Seq[CreatedEvent]]

  def activeContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      party: PartyId,
      companion: ContractCompanion[TC, TCid, T],
  ): Future[Seq[Contract[TCid, T]]]

  def subscribeAsync(
      subscriptionName: String,
      offset: LedgerOffset,
      filter: TransactionFilter,
  )(f: Transaction => Future[Unit]): CoinLedgerSubscription

  def tryGetTransactionTreeById(parties: Seq[PartyId], id: String): Future[TransactionTree]

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

  def listPackages()(implicit traceContext: TraceContext): Future[Set[String]]
  def uploadDarFile(pkg: UploadablePackage)(implicit traceContext: TraceContext): Future[Unit]
  def uploadDarFile(path: Path)(implicit traceContext: TraceContext): Future[Unit]

  def time()(implicit traceContext: TraceContext): Source[CantonTimestamp, Cancellable]
}

/** Subscription for reading the ledger */
trait CoinLedgerSubscription extends FlagCloseableAsync with NamedLogging {
  val completed: Future[Done]
}

object CoinLedgerConnection {

  /** Abstract representation of a command-id for deduplication.
    * @param methodName: fully-classified name of the method whose calls should be deduplicated,
    *   e.g., "com.daml.network.directory.createDirectoryEntry". DON'T USE [[io.functionmeta.functionFullName]] here,
    *   as it is not consistent across updates and restarts.
    * @param parties: list of parties whose method calls should be considered distinct,
    *   e.g., "Seq(directoryProvider)"
    * @param discriminator: additional discriminator for method calls,
    *   e.g., "digitalasset.cn" in case of deduplicating directory entry requests relating to directory name "digitalasset.cn". Beware of naive concatenation
    *   strings for discriminators. Always ensure that the encoding is injective.
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
      maxRetries: Int,
      workflowId: String,
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
      )(implicit traceContext: TraceContext): Future[Transaction] = {
        client.submitAndWaitForTransaction(
          workflowId = workflowId,
          applicationId = coinLedgerClient.applicationId,
          commandId = uniqueId,
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          deduplicationOffset = None,
        )
      }

      def submitCommands(
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          commands: Seq[Command],
          commandId: CommandId,
          deduplicationOffset: String,
      )(implicit traceContext: TraceContext): Future[Transaction] = {
        client.submitAndWaitForTransaction(
          workflowId = workflowId,
          applicationId = coinLedgerClient.applicationId,
          commandId = commandId.commandIdForSubmission,
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          deduplicationOffset = Some(deduplicationOffset),
        )
      }

      def submitWithResultNoDedup[T](
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          update: Update[T],
      )(implicit traceContext: TraceContext): Future[T] =
        submitWithResultAndOffsetNoDedup(actAs, readAs, update).map(_._2)

      def submitWithResultAndOffsetNoDedup[T](
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          update: Update[T],
      )(implicit traceContext: TraceContext): Future[(String, T)] = {
        for {
          tree <- client.submitAndWaitForTransactionTree(
            workflowId = workflowId,
            applicationId = coinLedgerClient.applicationId,
            commandId = uniqueId,
            actAs = actAs.map(_.toProtoPrimitive),
            readAs = readAs.map(_.toProtoPrimitive),
            commands = update.commands.asScala.toSeq,
            deduplicationOffset = None,
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
          filter: TransactionFilter
      ): Future[(Seq[CreatedEvent], LedgerOffset)] = {
        val activeContractsRequest =
          client.activeContracts(new GetActiveContractsRequest("", filter, false))
        activeContractsRequest.toMat(Sink.seq)(Keep.right).run().map { responseSequence =>
          val offset = responseSequence
            .map(_.getOffset.toScala)
            .lastOption
            .flatten
            // according to spec, should always be defined in last message of stream
            .getOrElse(
              throw new RuntimeException(
                "Expected to have offset in the last message of the acs stream but didn't have one!"
              )
            )
          val active = responseSequence.flatMap(_.getCreatedEvents.asScala)
          (active, new LedgerOffset.Absolute(offset))
        }
      }

      override def activeContractsWithOffset[TC <: Contract[TCid, T], TCid <: ContractId[
        T
      ], T <: Template](
          party: PartyId,
          companion: ContractCompanion[TC, TCid, T],
      ): Future[(Seq[Contract[TCid, T]], LedgerOffset)] =
        activeContractsWithOffset(
          CoinLedgerConnection.transactionFilterByParty(
            Map(party -> Seq(companion.TEMPLATE_ID))
          )
        ).map { case (events, off) =>
          (events.map(companion.fromCreatedEvent(_)), off)
        }

      override def activeContracts(
          filter: TransactionFilter
      ): Future[Seq[CreatedEvent]] =
        activeContractsWithOffset(filter).map(_._1)

      override def activeContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
          party: PartyId,
          companion: ContractCompanion[TC, TCid, T],
      ): Future[Seq[Contract[TCid, T]]] =
        activeContractsWithOffset(party, companion).map(_._1)

      private def subscription[T](
          subscriptionName: String,
          offset: LedgerOffset,
          filter: TransactionFilter,
      )(mapOperator: Flow[Transaction, Any, _]): CoinLedgerSubscription =
        makeSubscription(
          client.transactions(new GetTransactionsRequest("", offset, filter, false)),
          Flow
            .fromFunction[GetTransactionsResponse, Seq[Transaction]](
              _.getTransactions.asScala.toSeq
            )
            .mapConcat(identity)
            .via(mapOperator),
          subscriptionName,
        )
      override def subscribeAsync(
          subscriptionName: String,
          offset: LedgerOffset,
          filter: TransactionFilter,
      )(f: Transaction => Future[Unit]): CoinLedgerSubscription =
        subscription(subscriptionName, offset, filter)({
          Flow[Transaction].mapAsync(1)(f)
        })

      private def makeSubscription[S, T](
          source: Source[S, NotUsed],
          mapOperator: Flow[S, T, _],
          subscriptionName: String,
      ): CoinLedgerSubscription =
        new CoinLedgerSubscription {
          override protected def timeouts: ProcessingTimeout = coinLedgerClient.timeouts
          import TraceContext.Implicits.Empty._
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
          override val loggerFactory =
            if (subscriptionName.isEmpty)
              loggerFactoryForCoinLedgerConnectionOverride
            else
              loggerFactoryForCoinLedgerConnectionOverride.appendUnnamedKey(
                "subscription",
                subscriptionName,
              )

          override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
            import TraceContext.Implicits.Empty._
            List[AsyncOrSyncCloseable](
              SyncCloseable(s"killSwitch.shutdown $subscriptionName", killSwitch.shutdown()),
              AsyncCloseable(
                s"graph.completed $subscriptionName",
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

      // TODO(M1-92): Factor out user/party allocation and make it robust (current implementation is racy)
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
          // TODO(M1-90): The ledger API does not block until the package is vetted.
          //  Need to wait a bit, or use the Canton admin API to upload the package (that one does block).
          _ = Threading.sleep(500)
          _ = logger.info(s"Package ${pkg.packageId} is uploaded")
        } yield ()
      }

      override def uploadDarFile(
          path: Path
      )(implicit traceContext: TraceContext): Future[Unit] = {
        for {
          darFile <- Future { ByteString.readFrom(Files.newInputStream(path)) }
          // TODO(M1-90) Consider if we want to be clever
          // and only upload if it has not already been uploaded.
          _ <- client.uploadDarFile(darFile)
          // TODO(M1-90): The ledger API does not block until the package is vetted.
          //  Need to wait a bit, or use the Canton admin API to upload the package (that one does block).
          _ = Threading.sleep(500)
          _ = logger.info(s"DAR $path is uploaded")
        } yield ()
      }

      override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = List[AsyncOrSyncCloseable]()

      override def time()(implicit
          traceContext: TraceContext
      ): Source[CantonTimestamp, Cancellable] = {
        client
          .time()
          .map(response =>
            CantonTimestamp
              .fromProtoPrimitive(Timestamp.fromJavaProto(response.getCurrentTime)) match {
              case Left(err) =>
                throw new RuntimeException(
                  s"Could not parse timestamp received from ledger API: $err"
                )
              case Right(timestamp) => timestamp
            }
          )
      }
    }

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

  def transactionFilterByParty(filter: Map[PartyId, Seq[Identifier]]): TransactionFilter =
    new FiltersByParty(
      filter
        .map[String, Filter] { case (p, ts) =>
          p.toProtoPrimitive -> InclusiveFilter.ofTemplateIds(ts.toSet.asJava)
        }
        .asJava
    )

  /** Same as [[transactionFilterByParty]] but for interfaces. */
  def transactionInterfaceFilterByParty(filter: Map[PartyId, Seq[Identifier]]): TransactionFilter =
    new FiltersByParty(
      filter
        .map[String, Filter] { case (p, is) =>
          p.toProtoPrimitive -> new InclusiveFilter(
            Set.empty.asJava,
            is.map(i => i -> Filter.Interface.INCLUDE_VIEW).toMap.asJava,
          )
        }
        .asJava
    )

  def uniqueId: String = UUID.randomUUID.toString
}
