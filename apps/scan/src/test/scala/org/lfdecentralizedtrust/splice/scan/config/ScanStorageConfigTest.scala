package org.lfdecentralizedtrust.splice.scan.config

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.scalatest.wordspec.AnyWordSpec

class ScanStorageConfigTest
    extends AnyWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  "ScanStorageConfig" should {
    "computeSnapshotTimeAfter" should {
      "return correct time if the previous one is not a valid snapshot time" in {
        val config = ScanStorageConfig(
          dbAcsSnapshotPeriodHours = 2,
          bulkAcsSnapshotPeriodHours = 4,
          bulkDbReadChunkSize = 1,
          bulkZstdFrameSize = 0L,
          bulkMaxFileSize = 0L,
        )
        val prev = cantonTimestamp("2007-12-03T11:30:00.00Z")
        val next = cantonTimestamp("2007-12-03T12:00:00.00Z")
        config.computeDbSnapshotTimeAfter(prev) shouldBe next
      }
      "return correct time if the previous one is a valid snapshot time" in {
        val config = ScanStorageConfig(
          dbAcsSnapshotPeriodHours = 2,
          bulkAcsSnapshotPeriodHours = 4,
          bulkDbReadChunkSize = 1,
          bulkZstdFrameSize = 0L,
          bulkMaxFileSize = 0L,
        )
        val prev = cantonTimestamp("2007-12-03T12:00:00.00Z")
        val next = cantonTimestamp("2007-12-03T14:00:00.00Z")
        config.computeDbSnapshotTimeAfter(prev) shouldBe next
      }
      "return correct time if the next one is on the day after" in {
        val config = ScanStorageConfig(
          dbAcsSnapshotPeriodHours = 4,
          bulkAcsSnapshotPeriodHours = 8,
          bulkDbReadChunkSize = 1,
          bulkZstdFrameSize = 0L,
          bulkMaxFileSize = 0L,
        )
        val prev = cantonTimestamp("2007-12-03T21:00:00.00Z")
        val next = cantonTimestamp("2007-12-04T00:00:00.00Z")
        config.computeDbSnapshotTimeAfter(prev) shouldBe next
      }
    }
  }

  private def cantonTimestamp(isoStr: String) =
    CantonTimestamp.assertFromInstant(java.time.Instant.parse(isoStr))
}
