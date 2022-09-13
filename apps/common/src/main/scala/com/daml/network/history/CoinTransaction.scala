package com.daml.network.history

import cats.syntax.traverse._
import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter
import com.google.protobuf.timestamp.Timestamp

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
    // TODO(tech-debt): add validation for all of these and switch to types from com.daml.ledger.api.domain.
    Right(TransactionMetadata(transactionId, commandId, workflowId, effectiveAt, offset))
  }
}
