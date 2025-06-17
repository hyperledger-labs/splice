// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment.ledger.api

import com.daml.ledger.javaapi.data.Transaction
import com.digitalasset.canton.data.CantonTimestamp

sealed abstract class TreeUpdate extends Product with Serializable {
  def recordTime: CantonTimestamp
  def updateId: String
  def offset: Long
}

final case class TransactionTreeUpdate(
    tree: Transaction
) extends TreeUpdate {
  override def recordTime = CantonTimestamp.assertFromInstant(tree.getRecordTime)

  override def updateId: String = tree.getUpdateId
  override def offset = tree.getOffset
}

final case class ReassignmentUpdate(transfer: Reassignment[ReassignmentEvent]) extends TreeUpdate {
  override def recordTime = transfer.recordTime

  override def updateId: String = transfer.updateId
  override def offset = transfer.offset
}
