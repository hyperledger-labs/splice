package com.daml.network.util

import com.daml.network.config.GcpBucketConfig
import com.daml.network.identities.{NodeIdentitiesDump, NodeIdentitiesStore}
import com.daml.network.integration.tests.SpliceTests.TestCommon
import com.digitalasset.canton.topology.ParticipantId

import java.nio.file.{Path, Paths}
import java.time.Instant

trait DataExportTestUtil extends TestCommon {
  this: CommonAppInstanceReferences =>

  def testRecentDump[A](
      namespace: String,
      getFileName: Instant => Path,
      decode: String => Either[String, A],
  ) = {
    val bucket = new GcpBucket(GcpBucketConfig.inferForCluster, loggerFactory)
    import java.time.Instant
    import java.time.temporal.ChronoUnit
    val cluster = sys.env("GCP_CLUSTER_BASENAME")
    def name(instant: Instant) =
      s"$cluster/$namespace/${getFileName(instant)}"
    val now = Instant.now
    // Query everything within the last 20min and check that we have at least one.
    val blobs = bucket.list(name(now.plus(-20, ChronoUnit.MINUTES)), name(now))
    blobs should not be empty
    forAll(blobs) { blob =>
      val dump = bucket.readStringFromBucket(Paths.get(blob.getName))
      decode(dump) should matchPattern { case Right(_) =>
      }
    }
  }

  def testRecentParticipantIdentitiesDump(namespace: String) =
    testRecentDump(
      namespace,
      NodeIdentitiesStore.dumpFilename(_),
      NodeIdentitiesDump.fromJsonString(ParticipantId.tryFromProtoPrimitive, _),
    )
}
