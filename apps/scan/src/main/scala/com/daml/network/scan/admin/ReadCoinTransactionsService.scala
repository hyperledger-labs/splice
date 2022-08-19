package com.daml.network.scan.admin

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.event.ExercisedEvent
import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.{Created, Empty, Exercised}
import com.daml.ledger.api.v1.transaction.{Transaction, TransactionTree, TreeEvent}
import com.daml.ledger.client.binding.{Primitive, ValueDecoder, Value => CodegenValue}
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
import com.digitalasset.network.CC.Coin.{Coin, Coin_Unlock, LockedCoin}
import com.digitalasset.network.CC.CoinRules.{
  CoinRules,
  CoinRules_MiningRound_StartIssuing,
  CoinRules_Tap,
  CoinRules_Transfer,
  TransferResult,
}
import com.digitalasset.network.CC.Round.IssuingMiningRound

import scala.collection.{concurrent, mutable}
import scala.concurrent.{ExecutionContext, Future}
import com.daml.ledger.api.v1

import scala.collection.concurrent.TrieMap

class ReadCoinTransactionsService(
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

  private val activeCoins: concurrent.Map[Primitive.ContractId[Coin], Contract[Coin]] =
    TrieMap[Primitive.ContractId[Coin], Contract[Coin]]()

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
                val coinContract = Contract.fromCodegenContract(c)
                activeCoins.addOne((coinContract.contractId, coinContract))
                val res = CoinEvent(
                  CoinCreate(coinContract),
                  parseParentEvent(pathToNode.lastOption),
                )
                coinEvents.append(res)
              })
          case exercised: Exercised =>
            val coinContractOpt = DecodeUtil.decodeArchivedExercise(Coin)(exercised)
            coinContractOpt.foreach(cid => {
              val coinContract = activeCoins
                .remove(cid)
                .getOrElse(
                  ErrorUtil.invalidState(
                    s"Observed an archive for the coin contract with contract id $cid, but we haven't observed the corresponding create"
                  )
                )
              coinEvents.append(
                CoinEvent(CoinArchive(coinContract), parseParentEvent(pathToNode.lastOption))
              )
            })
            val children = exercised.value.childEventIds.map(tree.eventsById(_).kind)
            stack.pushAll(children.map((_, pathToNode :+ node)).reverse)
        }
      }
      coinEvents
    }

    // node and path to that node
    type StackElement = (TreeEvent.Kind, Seq[TreeEvent.Kind])

    val coinEvents: mutable.Buffer[CoinEvent] = mutable.ListBuffer()
    val roots = tree.rootEventIds.map(id => tree.eventsById(id).kind)
    val stack: mutable.Stack[StackElement] = mutable.Stack()
    stack.pushAll(roots.map((_, Seq())).reverse)
    preorder(stack, coinEvents).toSeq
  }

  // Some helper methods
  private val rulesTemplate = ApiTypes.TemplateId.unwrap(CoinRules.id)
  private val lockedCoinTemplate = ApiTypes.TemplateId.unwrap(LockedCoin.id)
  private def isCoinRules(event: ExercisedEvent) = event.templateId.contains(rulesTemplate)
  private def isLockedCoin(event: ExercisedEvent) = event.templateId.contains(lockedCoinTemplate)

  private def isTap(event: ExercisedEvent) = event.choice == "CoinRules_Tap" && isCoinRules(event)
  private def isCoinUnlock(event: ExercisedEvent) =
    event.choice == "Coin_Unlock" && isLockedCoin(event)
  private def isTransfer(event: ExercisedEvent) =
    event.choice == "CoinRules_Transfer" && isCoinRules(event)
  private def isStartIssuing(event: ExercisedEvent) =
    event.choice == "CoinRules_MiningRound_StartIssuing" && isCoinRules(event)

  private def tryDecode[A](
      value: v1.value.Value
  )(implicit A: ValueDecoder[A]): A = {
    CodegenValue.decode[A](value) match {
      case None =>
        ErrorUtil.invalidState(
          s"Unexpectedly couldn't decode LF-value $value to $A. Did you specify the wrong type to decode to?"
        )
      case Some(value) => value
    }
  }

  import cats.syntax.option._
  private def parseParentEvent(parent: Option[TreeEvent.Kind]): Option[ParentNode] = {
    parent match {
      case None => // coin create or archival has no parent node
        logger.warn("Ancestor of Coin had no parent node in the transaction tree")
        None
      case Some(parent) =>
        parent match {
          case exercised: Exercised if isTransfer(exercised.value) =>
            val argument = tryDecode[CoinRules_Transfer](exercised.value.getChoiceArgument)
            val result =
              tryDecode[Primitive.List[TransferResult]](exercised.value.getExerciseResult)
            Transfer(argument, result).some
          case exercised: Exercised if isTap(exercised.value) =>
            val argument = tryDecode[CoinRules_Tap](exercised.value.getChoiceArgument)
            val result = tryDecode[Primitive.ContractId[Coin]](exercised.value.getExerciseResult)
            Tap(argument, result).some
          case exercised: Exercised if isStartIssuing(exercised.value) =>
            val argument =
              tryDecode[CoinRules_MiningRound_StartIssuing](exercised.value.getChoiceArgument)
            val result =
              tryDecode[Primitive.ContractId[IssuingMiningRound]](exercised.value.getExerciseResult)
            StartIssuing(argument, result).some
          case exercised: Exercised if isCoinUnlock(exercised.value) =>
            val argument =
              tryDecode[Coin_Unlock](exercised.value.getChoiceArgument)
            val result =
              tryDecode[Primitive.ContractId[Coin]](exercised.value.getExerciseResult)
            CoinUnlock(argument, result).some
          case other: Exercised =>
            logger.warn(
              s"Parent of coin create or archival was not a tap, transfer or start issuing but ${other.getClass} ($other)"
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
