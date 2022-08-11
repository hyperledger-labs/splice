package com.daml.network.scan.admin

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.event.ExercisedEvent
import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.{Created, Empty, Exercised}
import com.daml.ledger.api.v1.transaction.{Transaction, TransactionTree, TreeEvent}
import com.daml.ledger.client.binding.{
  Contract,
  ValueDecoder,
  Primitive => P,
  Value => CodegenValue,
}
import com.daml.network.admin.LedgerAutomationService
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.scan.admin.ReadCcTransfersService._
import com.daml.network.scan.store.ScanTransferStore
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.CC.CoinRules.{
  CoinRules,
  CoinRules_MiningRound_StartIssuing,
  CoinRules_Tap,
  CoinRules_Transfer,
}

import scala.annotation.nowarn
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import cats.syntax.traverse._

class ReadCcTransfersService(
    connection: CoinLedgerConnection,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, tc: TraceContext)
    extends LedgerAutomationService
    with NamedLogging {

  @nowarn
  val store: ScanTransferStore = ???

  /** This works as follows:
    * - read the flat transaction stream filtered for `Coin` creates and archivals
    * - for each create or archival, grab the corresponding tx tree
    * - traverse the corresponding tx tree to find the creates and archivals and then add them to TxStore with the context
    * derived from the full transaction tree
    */
  override def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      treeO <- connection
        .transactionTreeById(Seq(), tx.transactionId)
      tree = treeO.getOrElse(
        sys.error(
          s"Unexpectedly, the Ledger API didn't know the transaction tree associated with transaction $tx"
        )
      )
      events <- traverseForest(tree)
      _ <- Future.apply(store.addCoinEvent(events))
    } yield ()
  }

  /** Traverse a TransactionTree via pre-order DFS */
  private def traverseForest(tree: TransactionTree): Future[Seq[CoinEvent]] = {
    val coinEvents: mutable.Buffer[CoinEvent] = mutable.ListBuffer()
    // TODO(Arne): make this stack-safe
    def preOrderDfs(node: TreeEvent.Kind, pathToNode: Seq[TreeEvent.Kind]): Future[Unit] = {
      Future {
        node match {
          case Empty =>
          case created: Created =>
            DecodeUtil
              .decodeCreated(Coin)(created.value)
              .foreach(c =>
                coinEvents.append(CoinEvent(Left(c), createContext(pathToNode.lastOption)))
              )
          case exercised: Exercised =>
            val coinContractOpt = DecodeUtil.decodeArchivedExercise(Coin)(exercised)
            coinContractOpt.foreach(cid =>
              CoinEvent(Right(cid), createContext(pathToNode.lastOption))
            )
            val children = exercised.value.childEventIds.map(tree.eventsById(_).kind)
            children.foreach(preOrderDfs(_, pathToNode :+ node))
        }
      }
    }

    for {
      roots <- Future(tree.rootEventIds.map(id => tree.eventsById(id).kind))
      _ <- roots.traverse(preOrderDfs(_, Seq()))
    } yield coinEvents.toSeq

  }

  // Some helper methods
  private val rulesTemplate = ApiTypes.TemplateId.unwrap(CoinRules.id)
  private def isCoinRules(event: ExercisedEvent) = event.templateId.contains(rulesTemplate)

  private def isTap(event: ExercisedEvent) = event.choice == "CoinRules_Tap" && isCoinRules(event)
  private def isTransfer(event: ExercisedEvent) =
    event.choice == "CoinRules_Transfer" && isCoinRules(event)
  private def isStartIssuing(event: ExercisedEvent) =
    event.choice == "CoinRules_MiningRound_StartIssuing" && isCoinRules(event)

  private def attemptDecode[A](exercised: Exercised)(implicit A: ValueDecoder[A]) =
    CodegenValue.decode[A](exercised.value.getChoiceArgument) match {
      case None =>
        ErrorUtil.invalidState(
          s"Unexpectedly couldn't decode $exercised to a ${exercised.value.choice}"
        )
      case Some(value) => value
    }

  private def createContext(parent: Option[TreeEvent.Kind]): CcTransactionContext = {
    parent match {
      case None => // coin create or archival has no parent node
        BareCreateOrArchival()
      case Some(parent) =>
        parent match {
          case exercised: Exercised if isTransfer(exercised.value) =>
            Transfer(attemptDecode[CoinRules_Transfer](exercised))
          case exercised: Exercised if isTap(exercised.value) =>
            Tap(attemptDecode[CoinRules_Tap](exercised))
          case exercised: Exercised if isStartIssuing(exercised.value) =>
            StartIssuing(attemptDecode[CoinRules_MiningRound_StartIssuing](exercised))
          case other: Exercised =>
            logger.info(s"Parent of coin create or archival was not a tap or transfer but $other")
            UnknownExerciseParent()
          case created: Created =>
            ErrorUtil.invalidState(
              "Observed that the parent of a Coin create or archival is a `Created` node" +
                s"$created. However, a `Created` node should never have any children. "
            )
          case Empty =>
            BareCreateOrArchival()
        }
    }
  }

  override def close(): Unit = Lifecycle.close(connection)(logger)

}

object ReadCcTransfersService {

  /** Context in which a CC create or archival occurred. */
  sealed trait CcTransactionContext
  // TODO(Arne): convert into codegen representations
  case class Transfer(transfer: CoinRules_Transfer) extends CcTransactionContext
  case class Tap(tap: CoinRules_Tap) extends CcTransactionContext
  case class StartIssuing(startIssuing: CoinRules_MiningRound_StartIssuing)
      extends CcTransactionContext
  case class UnknownExerciseParent() extends CcTransactionContext
  case class UnknownExercise() extends CcTransactionContext
  case class BareCreateOrArchival() extends CcTransactionContext

  case class CoinEvent(
      coin: Either[Contract[Coin], P.ContractId[Coin]],
      context: CcTransactionContext,
  )

}
