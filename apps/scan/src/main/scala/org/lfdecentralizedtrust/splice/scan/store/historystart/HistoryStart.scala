// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.historystart

import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig

sealed trait HistoryStart {
  def firstOwnSegmentStart(storageConfig: ScanStorageConfig): CantonTimestamp
}

object HistoryStart {
  case object Genesis extends HistoryStart {
    override def firstOwnSegmentStart(storageConfig: ScanStorageConfig): CantonTimestamp =
      CantonTimestamp.MinValue
  }

  final case class From(recordTime: CantonTimestamp) extends HistoryStart {
    override def firstOwnSegmentStart(storageConfig: ScanStorageConfig): CantonTimestamp =
      storageConfig.computeBulkSnapshotTimeAfter(recordTime)
  }
}
