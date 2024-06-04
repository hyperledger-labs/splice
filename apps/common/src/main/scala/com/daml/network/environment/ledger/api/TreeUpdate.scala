package com.daml.network.environment.ledger.api

import com.daml.ledger.javaapi.data.TransactionTree
import com.digitalasset.canton.data.CantonTimestamp

sealed abstract class TreeUpdate extends Product with Serializable {
  def recordTime: CantonTimestamp
}

final case class TransactionTreeUpdate(
    tree: TransactionTree
) extends TreeUpdate {
  override def recordTime = CantonTimestamp.assertFromInstant(tree.getRecordTime)
}

final case class ReassignmentUpdate(transfer: Reassignment[ReassignmentEvent]) extends TreeUpdate {
  override def recordTime = transfer.recordTime
}
