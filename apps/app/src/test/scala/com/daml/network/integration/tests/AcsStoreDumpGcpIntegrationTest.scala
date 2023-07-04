package com.daml.network.integration.tests

import com.daml.network.config.GcpBucketConfig
import com.daml.network.util.GcpBucket
import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AsyncWordSpec

import java.nio.file.Paths
import java.util.UUID

// Integration test to see that we can write to and read from a GCP bucket
class AcsStoreDumpGcpIntegrationTest extends AsyncWordSpec with BaseTest {

  val bucket = new GcpBucket(GcpBucketConfig.inferForTesting, loggerFactory)

  "gcp" should {
    "support writing to and reading from a gcp bucket" in {
      // Dump bytes to GCP bucket
      val originalText = "Hello, GCP!"
      val dataToDump = originalText
      val fileName = Paths.get("integration-test", s"dummy_${UUID.randomUUID()}.txt")
      bucket.dumpStringToBucket(dataToDump, fileName)

      // Read bytes from GCP bucket
      val retrievedText = bucket.readStringFromBucket(fileName)
      logger.info(s"Retrieved data: $retrievedText")

      originalText shouldBe retrievedText

    }
  }

}
