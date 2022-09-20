package com.daml.network.svc.admin

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1
import com.daml.ledger.api.v1.event.ExercisedEvent
import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.Exercised
import com.daml.ledger.api.v1.transaction.{Transaction, TransactionTree}
import com.daml.ledger.client.binding.{Primitive, Value => CodegenValue, ValueDecoder}
import com.daml.network.admin.LedgerAutomationService
import com.daml.network.codegen.CC.Coin.{Coin, LockedCoin}
import com.daml.network.codegen.CC.CoinRules.{CoinRules, TransferResult}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.history._
import com.daml.network.svc.store.SvcAppStore
import com.daml.network.util.{ExerciseNode, ExerciseNodeCompanion, Trees}
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class RoundSummaryCollectionService(
    svcParty: PartyId,
    connection: CoinLedgerConnection,
    store: SvcAppStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, tc: TraceContext)
    extends LedgerAutomationService
    with NamedLogging {

  override def templateIds: Seq[Primitive.TemplateId[_]] = Seq(Coin.id, LockedCoin.id)

  override def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      treeO <- connection.transactionTreeById(Seq(svcParty), tx.transactionId)
      tree = treeO.getOrElse(
        sys.error(
          s"Unexpectedly, the Ledger API didn't know the transaction tree associated with transaction $tx"
        )
      )
      transfers <- traverseForest(tree)
      _ <- store.addTransfers(transfers)
    } yield ()
  }

  def traverseForest(tree: TransactionTree): Future[Seq[TransferResult]] =
    Future {
      val transferResults: mutable.Buffer[TransferResult] = mutable.ListBuffer()
      Trees.traverseTree(
        tree,
        onCreate = (_, _) => {},
        onExercise = (exercised: Exercised, _) => {
          if (isTransfer(exercised.value)) {
            transferResults.append(Transfer(tryDecodeEvent(Transfer)(exercised)).node.result)
          }
        },
      )

      transferResults.toSeq
    }

  override def close(): Unit = Lifecycle.close(connection)(logger)

  //TODO(i775) all code below has been copied over from scan's ReadCoinTransactionService. Move it to some shared util place instead - potentially to ExerciseNodeCompanion.

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

  private def tryDecodeEvent(companion: ExerciseNodeCompanion)(exercised: Exercised)(implicit
      decArg: ValueDecoder[companion.Arg],
      decRes: ValueDecoder[companion.Res],
  ): ExerciseNode[companion.Arg, companion.Res] =
    ExerciseNode(
      tryDecode[companion.Arg](exercised.value.getChoiceArgument),
      tryDecode[companion.Res](exercised.value.getExerciseResult),
    )

  private val rulesTemplate = ApiTypes.TemplateId.unwrap(CoinRules.id)
  private def isTransfer(event: ExercisedEvent) =
    event.choice == "CoinRules_Transfer" && isCoinRules(event)
  private def isCoinRules(event: ExercisedEvent) = event.templateId.contains(rulesTemplate)

}
