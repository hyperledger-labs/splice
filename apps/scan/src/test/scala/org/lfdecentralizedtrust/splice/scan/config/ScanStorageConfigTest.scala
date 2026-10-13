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
      def mkConfig(periodHours: Int) = ScanStorageConfig(
        dbAcsSnapshotPeriodHours = periodHours,
        bulkAcsSnapshotPeriodHours = 4,
        bulkZstdBlockSize = 0L,
        bulkZstdFrameSize = 0L,
        bulkMaxFileSize = 0L,
        zstdCompressionLevel = 0,
      )

      "return correct time if the previous one is not a valid snapshot time" in {
        val config = mkConfig(periodHours = 2)
        val prev = cantonTimestamp("2007-12-03T11:30:00.00Z")
        val next = cantonTimestamp("2007-12-03T12:00:00.00Z")
        config.computeDbSnapshotTimeAfter(prev) shouldBe next
      }
      "return correct time if the previous one is a valid snapshot time" in {
        val config = mkConfig(periodHours = 2)
        val prev = cantonTimestamp("2007-12-03T12:00:00.00Z")
        val next = cantonTimestamp("2007-12-03T14:00:00.00Z")
        config.computeDbSnapshotTimeAfter(prev) shouldBe next
      }
      "return correct time if the next one is on the day after" in {
        val config = mkConfig(periodHours = 4)
        val prev = cantonTimestamp("2007-12-03T21:00:00.00Z")
        val next = cantonTimestamp("2007-12-04T00:00:00.00Z")
        config.computeDbSnapshotTimeAfter(prev) shouldBe next
      }
    }

    "find the segment folder of an object key and parse it back to the segment" in {
      val config = ScanStorageConfigs.scanStorageConfigV1
      val from = cantonTimestamp("2007-12-03T00:00:00.00Z")
      val to = cantonTimestamp("2007-12-04T00:00:00.00Z")
      val folder = config.getSegmentFolder(from, Some(to))
      val key = s"$folder/${ScanStorageConfig.Encoding.ProtobufJson.storageKey("updates", 3)}"

      config.getSegmentFolderOfObjectKey(key) shouldBe folder
      config.getStartAndEndTimestampsForFolder(config.getSegmentFolderOfObjectKey(key)) shouldBe
        Right((from, to))
    }
  }

  private def cantonTimestamp(isoStr: String) =
    CantonTimestamp.assertFromInstant(java.time.Instant.parse(isoStr))
}
