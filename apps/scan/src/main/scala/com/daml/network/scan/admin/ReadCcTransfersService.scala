package com.daml.network.scan.admin

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.event.ExercisedEvent
import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.{Created, Empty, Exercised}
import com.daml.ledger.api.v1.transaction.{Transaction, TransactionTree, TreeEvent}
import com.daml.ledger.client.binding.{ValueDecoder, Value => CodegenValue}
import com.daml.network.admin.LedgerAutomationService
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.history._
import com.daml.network.scan.store.ScanCCHistoryStore
import com.daml.network.util.Contract
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.CC.CoinRules.{
  CoinRules,
  CoinRules_MiningRound_StartIssuing,
  CoinRules_Tap,
  CoinRules_Transfer,
}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class ReadCcTransfersService(
    svcParty: PartyId,
    connection: CoinLedgerConnection,
    store: ScanCCHistoryStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, tc: TraceContext)
    extends LedgerAutomationService
    with NamedLogging {

  /** This works as follows:
    * - read the flat transaction stream filtered for `Coin` creates and archives
    * - for each create or archival, grab the corresponding tx tree
    * - traverse the corresponding TransactionTree to find the creates and archives and then add them to TxStore with the context
    * derived from the full transaction tree
    */
  override def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      treeO <- connection
        .transactionTreeById(Seq(svcParty), tx.transactionId)
      tree = treeO.getOrElse(
        sys.error(
          s"Unexpectedly, the Ledger API didn't know the transaction tree associated with transaction $tx"
        )
      )
      events <- traverseForest(tree)
      metadata = TransactionMetadata(tx)
      _ <- store.addTransaction(CoinTransaction(events, metadata))
    } yield ()
  }

  /** Traverse a TransactionTree via pre-order DFS */
  @SuppressWarnings(Array("org.wartremover.warts.While"))
  private def traverseForest(tree: TransactionTree): Future[Seq[CoinEvent]] = Future {

    def preorder(
        stack: mutable.Stack[StackElement],
        coinEvents: mutable.Buffer[CoinEvent],
    ): mutable.Buffer[CoinEvent] = {
      while (stack.nonEmpty) {
        val (node, pathToNode) = stack.pop()
        node match {
          case Empty =>
          case created: Created =>
            DecodeUtil
              .decodeCreated(Coin)(created.value)
              .foreach(c => {
                coinEvents.append(
                  CoinEvent(
                    CoinCreate(Contract.fromCodegenContract(c)),
                    parseParentEvent(pathToNode.lastOption),
                  )
                )
              })
          case exercised: Exercised =>
            val coinContractOpt = DecodeUtil.decodeArchivedExercise(Coin)(exercised)
            coinContractOpt.foreach(cid => {
              coinEvents.append(
                CoinEvent(CoinArchive(cid), parseParentEvent(pathToNode.lastOption))
              )
            })
            val children = exercised.value.childEventIds.map(tree.eventsById(_).kind)
            stack.pushAll(children.map((_, pathToNode :+ node)))
        }
      }
      coinEvents
    }

    // node and path to that node
    type StackElement = (TreeEvent.Kind, Seq[TreeEvent.Kind])

    val coinEvents: mutable.Buffer[CoinEvent] = mutable.ListBuffer()
    val roots = tree.rootEventIds.map(id => tree.eventsById(id).kind)
    val stack: mutable.Stack[StackElement] = mutable.Stack()
    stack.pushAll(roots.map((_, Seq())))
    preorder(stack, coinEvents).toSeq
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

  import cats.syntax.option._
  private def parseParentEvent(parent: Option[TreeEvent.Kind]): Option[AncestorEvent] = {
    parent match {
      case None => // coin create or archival has no parent node
        logger.warn("Ancestor of Coin had no parent node in the transaction tree")
        None
      case Some(parent) =>
        parent match {
          case exercised: Exercised if isTransfer(exercised.value) =>
            Transfer(attemptDecode[CoinRules_Transfer](exercised)).some
          case exercised: Exercised if isTap(exercised.value) =>
            Tap(attemptDecode[CoinRules_Tap](exercised)).some
          case exercised: Exercised if isStartIssuing(exercised.value) =>
            StartIssuing(attemptDecode[CoinRules_MiningRound_StartIssuing](exercised)).some
          case other: Exercised =>
            logger.warn(
              s"Parent of coin create or archival was not a tap, transfer or start issuing but $other"
            )
            None
          case created: Created =>
            ErrorUtil.invalidState(
              "Observed that the parent of a Coin create or archival is a `Created` node" +
                s"$created. However, a `Created` node should never have any children. "
            )
          case Empty =>
            logger.warn("Ancestor of Coin event in the transaction tree was an empty node")
            None
        }
    }
  }

  override def close(): Unit = Lifecycle.close(connection)(logger)

}

object ReadCcTransfersService {}
