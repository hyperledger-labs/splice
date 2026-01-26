// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

case class BulkStorageConfig(
    acsSnapshotPeriodHours: Int,
    dbReadChunkSize: Int,
    maxFileSize: Long,
) {
  require(
    acsSnapshotPeriodHours > 0 && 24 % acsSnapshotPeriodHours == 0,
    s"acsSnapshotPeriodHours must be a factor of 24 (received: $acsSnapshotPeriodHours)"
  )
}

object BulkStorageConfigs {
  val bulkStorageConfigV1 = BulkStorageConfig(
    acsSnapshotPeriodHours = 3,
    dbReadChunkSize = 1000,
    maxFileSize = 64L * 1024 * 1024,
  )
}
