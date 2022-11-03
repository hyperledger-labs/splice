package com.daml.network.environment

import akka.actor.ActorSystem
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.{Done, NotUsed}
import com.daml.ledger.javaapi.data.codegen.{Created, Exercised, Update}
import com.daml.ledger.javaapi.data.{
  Command,
  CreatedEvent,
  ExercisedEvent,
  GetActiveContractsRequest,
  GetTransactionsRequest,
  GetTransactionsResponse,
  LedgerOffset,
  Transaction,
  TransactionFilter,
  TransactionTree,
}
import com.digitalasset.canton.config.ProcessingTimeout
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
import io.grpc.StatusRuntimeException

import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success}

trait JavaCoinLedgerSubmit extends FlagCloseableAsync {
  def submitCommands(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
  )(implicit traceContext: TraceContext): Future[Transaction]

  // TODO(#790): make this the default, and name 'submitCommand' -> 'submitCommandNoDedup'; and introduce the submitResult variant
  def submitCommandsWithDedup(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      commandId: String,
      deduplicationOffset: String,
  )(implicit traceContext: TraceContext): Future[Transaction]

  def submitWithResult[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
  )(implicit traceContext: TraceContext): Future[T]
}

trait JavaCoinLedgerConnection extends JavaCoinLedgerSubmit {
  def activeContractsWithOffset(
      filter: TransactionFilter
  ): Future[(Seq[CreatedEvent], LedgerOffset)]

  def subscription[T](
      subscriptionName: String,
      offset: LedgerOffset,
      filter: TransactionFilter,
  )(mapOperator: Flow[Transaction, Any, _]): JavaCoinLedgerSubscription

  def subscribeAsync(
      subscriptionName: String,
      offset: LedgerOffset,
      filter: TransactionFilter,
  )(f: Transaction => Future[Unit]): JavaCoinLedgerSubscription

  // TODO(#790): this function should probably better live in a common.automation package
  def makeSubscription[S, T](
      source: Source[S, NotUsed],
      mapOperator: Flow[S, T, _],
      subscriptionName: String,
  ): JavaCoinLedgerSubscription
}

/** Subscription for reading the ledger */
trait JavaCoinLedgerSubscription extends FlagCloseableAsync with NamedLogging {
  val completed: Future[Done]
}

object JavaCoinLedgerConnection {

  def apply(
      coinLedgerClient: JavaCoinLedgerClient,
      maxRetries: Int,
      workflowId: String,
      loggerFactoryForCoinLedgerConnectionOverride: NamedLoggerFactory,
      tracerProvider: TracerProvider,
  ): JavaCoinLedgerConnection with NamedLogging =
    new JavaCoinLedgerConnection with NamedLogging {
      protected val loggerFactory: NamedLoggerFactory = loggerFactoryForCoinLedgerConnectionOverride

      override protected def timeouts: ProcessingTimeout = coinLedgerClient.timeouts
      private def client = coinLedgerClient.client
      implicit private def as: ActorSystem = coinLedgerClient.actorSystem
      implicit private def ec: ExecutionContextExecutor = coinLedgerClient.executionContextExecutor

      def submitCommands(
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

      // TODO(#790): make this the default, and name 'submitCommand' -> 'submitCommandNoDedup'; and introduce the submitResult variant
      def submitCommandsWithDedup(
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          commands: Seq[Command],
          commandId: String,
          deduplicationOffset: String,
      )(implicit traceContext: TraceContext): Future[Transaction] = {
        client.submitAndWaitForTransaction(
          workflowId = workflowId,
          applicationId = coinLedgerClient.applicationId,
          commandId = commandId,
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          commands = commands,
          deduplicationOffset = Some(deduplicationOffset),
        )
      }

      def submitWithResult[T](
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          update: Update[T],
      )(implicit traceContext: TraceContext): Future[T] = {
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
        } yield {
          decodeExerciseResult(
            update,
            tree,
          )
        }
      }

      private def decodeExerciseResult[T](
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

      override def subscription[T](
          subscriptionName: String,
          offset: LedgerOffset,
          filter: TransactionFilter,
      )(mapOperator: Flow[Transaction, Any, _]): JavaCoinLedgerSubscription =
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
      )(f: Transaction => Future[Unit]): JavaCoinLedgerSubscription =
        subscription(subscriptionName, offset, filter)({
          Flow[Transaction].mapAsync(1)(f)
        })

      override def makeSubscription[S, T](
          source: Source[S, NotUsed],
          mapOperator: Flow[S, T, _],
          subscriptionName: String,
      ): JavaCoinLedgerSubscription =
        new JavaCoinLedgerSubscription {
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
                  case Failure(ex: StatusRuntimeException) =>
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

      override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = List[AsyncOrSyncCloseable](
      )
    }

  def uniqueId: String = UUID.randomUUID.toString
}
