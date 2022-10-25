package com.daml.network.svc.automation

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.event.ExercisedEvent
import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.Exercised
import com.daml.ledger.api.v1.transaction.{Transaction, TransactionTree}
import com.daml.ledger.client.binding.Primitive
import com.daml.network.admin.LedgerAutomationService
import com.daml.network.codegen.CC.Coin.{Coin, LockedCoin}
import com.daml.network.codegen.CC.CoinRules.{CoinRules, TransferResult}
import com.daml.network.codegen.DA
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
            val tf = ExerciseNode.tryFromProtoEvent(Transfer)(exercised)
            tf.result match {
              case DA.Types.Either.Left(errorMsg) =>
                logger.debug(
                  s"Dropping transfer with input ${tf.argument} as it completed with an error: $errorMsg"
                )
              case DA.Types.Either.Right(result) => transferResults.append(result)
            }

          }
        },
      )

      transferResults.toSeq
    }

  override def close(): Unit = Lifecycle.close(connection)(logger)

  private val rulesTemplate = ApiTypes.TemplateId.unwrap(CoinRules.id)
  private def isTransfer(event: ExercisedEvent) =
    event.choice == "CoinRules_Transfer" && isCoinRules(event)
  private def isCoinRules(event: ExercisedEvent) = event.templateId.contains(rulesTemplate)

}
