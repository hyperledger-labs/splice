package com.daml.network.history

import cats.syntax.traverse._
import com.daml.ledger.api.v1.event.{CreatedEvent, ExercisedEvent}
import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.{Created, Exercised}
import com.daml.ledger.api.v1.transaction.{Transaction, TransactionTree, TreeEvent}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter
import com.google.protobuf.timestamp.Timestamp

/** Representation of a Coin transaction. A Coin transaction consists of at least one CoinEvent and meta-data
  * about the corresponding Daml transaction.
  */
case class CoinTransaction(events: Seq[CoinEvent], txMetadata: TransactionMetadata) {
  def toProtoV0: v0.CCTransaction =
    v0.CCTransaction(events.map(_.toProtoV0), metadata = Some(txMetadata.toProtoV0))
}

object CoinTransaction {
  def fromProtoV0(
      transactionP: v0.CCTransaction
  ): Either[ProtoDeserializationError, CoinTransaction] = for {
    events <- transactionP.events.traverse(CoinEvent.fromProtoV0)
    metadata <- ProtoConverter
      .required("CoinTransaction.metadata", transactionP.metadata)
      .flatMap(TransactionMetadata.fromProtoV0)
  } yield CoinTransaction(events, metadata)
}

/** Representation of a coin transaction with an ASCII-fied view of the transaction tree. */
case class CoinTransactionTreeView(
    transactionId: String,
    offset: String,
    forestOfEventsASCII: String,
)

object CoinTransactionTreeView {
  def fromTree(tree: TransactionTree): CoinTransactionTreeView =
    CoinTransactionTreeView(tree.transactionId, tree.offset, makeASCIITree(tree))

  def makeASCIITree(tree: TransactionTree): String = {
    def traverseTree(curr: TreeEvent.Kind, depth: Int): String = {
      lazy val indent = "\t" * depth
      curr match {
        case TreeEvent.Kind.Empty => ""
        case Created(created: CreatedEvent) =>
          s"""$indent create ${created.templateId}
             |$indent with
             |$indent  ${created.createArguments}
             |""".stripMargin
        case Exercised(exercised: ExercisedEvent) =>
          val children = {
            exercised.childEventIds
              .map(child => traverseTree(tree.eventsById(child).kind, depth + 1))
              .mkString(System.lineSeparator())
          }
          s"""$indent '${exercised.actingParties}' exercises ${exercised.choice}
             |$indent with
             |$indent ${exercised.choiceArgument}
             |$indent children:
             |$indent  $children
             |""".stripMargin

      }
    }

    val roots = tree.rootEventIds.map(rootId => tree.eventsById(rootId).kind)
    roots.map(r => traverseTree(r, 0)).mkString(System.lineSeparator())
  }
}

case class TransactionMetadata(
    transactionId: String,
    commandId: String,
    workflowId: String,
    effectiveAt: Option[Timestamp],
    offset: String,
) {
  def toProtoV0: v0.TransactionMetadata = {
    v0.TransactionMetadata(transactionId, commandId, workflowId, effectiveAt, offset)
  }
}

object TransactionMetadata {
  def apply(tx: Transaction): TransactionMetadata = {
    TransactionMetadata(tx.transactionId, tx.commandId, tx.workflowId, tx.effectiveAt, tx.offset)
  }

  def fromProtoV0(
      metadataP: v0.TransactionMetadata
  ): Either[ProtoDeserializationError, TransactionMetadata] = {
    val v0.TransactionMetadata(transactionId, commandId, workflowId, effectiveAt, offset, _) =
      metadataP
    // TODO(M1-92): add validation for all of these and switch to types from com.daml.ledger.api.domain.
    Right(TransactionMetadata(transactionId, commandId, workflowId, effectiveAt, offset))
  }
}
