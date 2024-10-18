// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment.ledger.api

import com.daml.ledger.javaapi.data.TransactionTree
import com.digitalasset.canton.data.CantonTimestamp

sealed abstract class TreeUpdate extends Product with Serializable {
  def recordTime: CantonTimestamp
  def updateId: String
}

final case class TransactionTreeUpdate(
    tree: TransactionTree
) extends TreeUpdate {
  override def recordTime = CantonTimestamp.assertFromInstant(tree.getRecordTime)

  override def updateId: String = tree.getUpdateId
}

final case class ReassignmentUpdate(transfer: Reassignment[ReassignmentEvent]) extends TreeUpdate {
  override def recordTime = transfer.recordTime

  override def updateId: String = transfer.updateId
}
