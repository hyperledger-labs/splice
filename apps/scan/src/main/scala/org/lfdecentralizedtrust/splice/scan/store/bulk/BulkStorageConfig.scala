// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

case class BulkStorageConfig(
    dbReadChunkSize: Int,
    maxFileSize: Long,
)

object BulkStorageConfigs {
  val bulkStorageConfigV1 = BulkStorageConfig(
    1000,
    64L * 1024 * 1024,
  )
}
