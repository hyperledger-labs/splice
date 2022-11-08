package com.daml.network.svc.automation

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.event.ExercisedEvent
import com.daml.ledger.api.v1.transaction.TransactionTree
import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.Exercised
import com.daml.ledger.javaapi.data.{Identifier, Transaction}
import com.daml.network.admin.LedgerAutomationService
import com.daml.network.codegen.CC.CoinRules.{CoinRules, TransferResult}
import com.daml.network.codegen.DA
import com.daml.network.codegen.java.cc
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
      transfers <- traverseForest(TransactionTree.fromJavaProto(tree.toProto))
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
