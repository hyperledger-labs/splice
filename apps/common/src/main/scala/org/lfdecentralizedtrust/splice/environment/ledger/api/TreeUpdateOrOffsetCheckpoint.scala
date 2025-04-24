// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment.ledger.api

import com.daml.ledger.javaapi.data.OffsetCheckpoint
import com.digitalasset.canton.topology.SynchronizerId

sealed abstract class TreeUpdateOrOffsetCheckpoint extends Product with Serializable {
  def offset: Long
}

object TreeUpdateOrOffsetCheckpoint {
  final case class Update(update: TreeUpdate, synchronizerId: SynchronizerId)
      extends TreeUpdateOrOffsetCheckpoint {
    override def offset = update.offset
  }
  final case class Checkpoint(checkpoint: OffsetCheckpoint) extends TreeUpdateOrOffsetCheckpoint {
    override def offset = checkpoint.getOffset
  }
}
