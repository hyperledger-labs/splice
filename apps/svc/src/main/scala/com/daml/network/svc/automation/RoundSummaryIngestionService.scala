package com.daml.network.svc.automation

import com.daml.ledger.javaapi.data.{ExercisedEvent, Identifier, TransactionTree}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.api.v1.coin.TransferResult
import com.daml.network.history.*
import com.daml.network.store.AuditLogIngestionSink
import com.daml.network.svc.store.SvcEventsStore
import com.daml.network.util.{ExerciseNode, Trees}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class RoundSummaryIngestionService(
    svcParty: PartyId,
    store: SvcEventsStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AuditLogIngestionSink
    with NamedLogging {

  override def filterParty: PartyId = svcParty

  override def templateIds: Seq[Identifier] =
    Seq(cc.coin.Coin.TEMPLATE_ID, cc.coin.LockedCoin.TEMPLATE_ID)

  override def processTransaction(tx: TransactionTree)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      transfers <- traverseForest(tx)
      _ <- store.addTransfers(transfers)
    } yield ()
  }
  def traverseForest(tree: TransactionTree)(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferResult]] =
    Future {
      val transferResults: mutable.Buffer[TransferResult] = mutable.ListBuffer()
      Trees.traverseTree(
        tree,
        onCreate = (_, _) => {},
        onExercise = (exercised: ExercisedEvent, _) => {
          ExerciseNode.decodeExerciseEvent(Transfer)(exercised).foreach { tf =>
            transferResults.append(tf.result.value)
          }
        },
      )

      transferResults.toSeq
    }
}
