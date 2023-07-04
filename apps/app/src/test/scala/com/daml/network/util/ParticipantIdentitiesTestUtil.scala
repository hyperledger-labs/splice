package com.daml.network.util

import com.daml.network.config.GcpBucketConfig
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.daml.network.validator.store.ParticipantIdentitiesStore

import java.nio.file.Paths

trait ParticipantIdentitiesTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences =>

  def testRecentParticipantIdentitiesDump(namespace: String) = {
    val bucket = new GcpBucket(GcpBucketConfig.inferForCluster, loggerFactory)
    import java.time.Instant
    import java.time.temporal.ChronoUnit
    val cluster = sys.env("GCP_CLUSTER_BASENAME")
    def name(instant: Instant) =
      s"$cluster/$namespace/${ParticipantIdentitiesStore.dumpFilename(instant)}"
    val now = Instant.now
    // Query everything within the last 20min and check that we have at least one.
    val blobs = bucket.list(name(now.plus(-20, ChronoUnit.MINUTES)), name(now))
    blobs should not be empty
    forAll(blobs) { blob =>
      val dump = bucket.readStringFromBucket(Paths.get(blob.getName))
      ParticipantIdentitiesDump.fromJsonString(dump) should matchPattern { case Right(_) =>
      }
    }
  }
}
