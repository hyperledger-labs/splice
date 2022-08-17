package com.daml.network.history

import com.daml.ledger.api.v1.transaction.Transaction
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.timestamp.Timestamp

case class CCTransaction(events: Seq[CCEvent], txMetadata: TransactionMetadata)

case class TransactionMetadata(
    transactionId: String,
    commandId: String,
    workflowId: String,
    effectiveAt: Option[Timestamp],
    offset: String,
)
object TransactionMetadata {
  def apply(tx: Transaction): TransactionMetadata = {
    TransactionMetadata(tx.transactionId, tx.commandId, tx.workflowId, tx.effectiveAt, tx.offset)
  }
}

case class CcTransfers(
    txId: String,
    eventId: String,
    recordTime: Timestamp,
    issuanceRound: Int,
    from: PartyId,
    to: PartyId,
    quantity: BigDecimal,
    fees: BigDecimal,
)
