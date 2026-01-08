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
