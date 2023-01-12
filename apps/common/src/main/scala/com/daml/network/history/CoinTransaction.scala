package com.daml.network.history

import cats.syntax.traverse._
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, TransactionTree, TreeEvent}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter
import com.google.protobuf.timestamp.Timestamp

import scala.jdk.CollectionConverters.*

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
    CoinTransactionTreeView(tree.getTransactionId, tree.getOffset, makeASCIITree(tree))

  def makeASCIITree(tree: TransactionTree): String = {
    def traverseTree(curr: TreeEvent, depth: Int): String = {
      lazy val indent = "\t" * depth
      curr match {
        case created: CreatedEvent =>
          s"""$indent create ${created.getTemplateId}
             |$indent with
             |$indent  ${created.getArguments}
             |""".stripMargin
        case exercised: ExercisedEvent =>
          val children = {
            exercised.getChildEventIds.asScala
              .map(child => traverseTree(tree.getEventsById.get(child), depth + 1))
              .mkString("\n")
          }
          s"""$indent '${exercised.getActingParties}' exercises ${exercised.getChoice}
             |$indent with
             |$indent ${exercised.getChoiceArgument}
             |$indent children:
             |$indent  $children
             |""".stripMargin
        case _ => sys.error(s"Unknown tree event type: $curr")
      }
    }

    val roots = tree.getRootEventIds.asScala.map(rootId => tree.getEventsById.get(rootId))
    roots.map(r => traverseTree(r, 0)).mkString("\n")
  }
}

case class TransactionMetadata(
    transactionId: String,
    commandId: String,
    workflowId: String,
    effectiveAt: Timestamp,
    offset: String,
) {
  def toProtoV0: v0.TransactionMetadata = {
    v0.TransactionMetadata(transactionId, commandId, workflowId, Some(effectiveAt), offset)
  }
}

object TransactionMetadata {
  def apply(tx: TransactionTree): TransactionMetadata = {
    val effectiveAt = Timestamp.of(tx.getEffectiveAt.getEpochSecond, tx.getEffectiveAt.getNano)
    TransactionMetadata(
      tx.getTransactionId,
      tx.getCommandId,
      tx.getWorkflowId,
      effectiveAt,
      tx.getOffset,
    )
  }

  def fromProtoV0(
      metadataP: v0.TransactionMetadata
  ): Either[ProtoDeserializationError, TransactionMetadata] = {
    val effectiveAtJava =
      Timestamp.of(metadataP.getEffectiveAt.seconds, metadataP.getEffectiveAt.nanos)
    Right(
      TransactionMetadata(
        metadataP.transactionId,
        metadataP.commandId,
        metadataP.workflowId,
        effectiveAtJava,
        metadataP.offset,
      )
    )
  }
}
