package com.daml.network.svc.automation

import com.daml.ledger.javaapi.data.{ExercisedEvent, Identifier, Transaction, TransactionTree}
import com.daml.network.admin.LedgerAutomationService
import com.daml.network.codegen.java.cc.coinrules.TransferResult
import com.daml.network.codegen.java.{cc, da}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.history.*
import com.daml.network.svc.store.SvcEventsStore
import com.daml.network.util.{ExerciseNode, Trees}
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class RoundSummaryCollectionService(
    svcParty: PartyId,
    connection: CoinLedgerConnection,
    store: SvcEventsStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, tc: TraceContext)
    extends LedgerAutomationService
    with NamedLogging {

  override def templateIds: Seq[Identifier] =
    Seq(cc.coin.Coin.TEMPLATE_ID, cc.coin.LockedCoin.TEMPLATE_ID)

  override def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      tree <- connection.tryGetTransactionTreeById(Seq(svcParty), tx.getTransactionId)
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
        onExercise = (exercised: ExercisedEvent, _) => {
          ExerciseNode.decodeExerciseEvent(Transfer)(exercised).foreach { tf =>
            tf.result.value match {
              case left: da.types.either.Left[_, _] =>
                logger.debug(
                  s"Dropping transfer with input ${tf.argument} as it completed with an error: $left.aValue"
                )
              case right: da.types.either.Right[_, _] => transferResults.append(right.bValue)
              case v => sys.error(s"Unexpected value for Either type: $v")
            }

          }
        },
      )

      transferResults.toSeq
    }

  override def close(): Unit = Lifecycle.close(connection)(logger)
}
