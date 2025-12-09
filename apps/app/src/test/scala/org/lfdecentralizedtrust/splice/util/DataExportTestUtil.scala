package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.config.{BucketName, GcpBucketConfig}
import org.lfdecentralizedtrust.splice.identities.{NodeIdentitiesDump, NodeIdentitiesStore}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import com.digitalasset.canton.topology.ParticipantId

import java.nio.file.{Path, Paths}
import java.time.Instant

trait DataExportTestUtil extends TestCommon {
  this: CommonAppInstanceReferences =>

  def testRecentDump[A](
      namespace: String,
      getFileName: Instant => Path,
      decode: String => Either[String, A],
      bucketName: BucketName,
  ) = {
    val bucket = new GcpBucket(GcpBucketConfig.inferForCluster(bucketName), loggerFactory)
    import java.time.Instant
    import java.time.temporal.ChronoUnit
    val cluster = sys.env("GCP_CLUSTER_BASENAME")
    def name(instant: Instant) =
      s"$cluster/$namespace/${getFileName(instant)}"
    val now = Instant.now
    // Query everything within the last 20min and check that we have at least one.
    val blobs = bucket.listBlobsByOffset(name(now.plus(-20, ChronoUnit.MINUTES)), name(now))
    blobs should not be empty
    forAll(blobs) { blob =>
      val dump = bucket.readStringFromBucket(Paths.get(blob.getName))
      decode(dump) should matchPattern { case Right(_) =>
      }
    }
  }

  def testRecentParticipantIdentitiesDump(namespace: String, bucketName: BucketName) =
    testRecentDump(
      namespace,
      NodeIdentitiesStore.dumpFilename(_),
      NodeIdentitiesDump.fromJsonString(ParticipantId.tryFromProtoPrimitive, _),
      bucketName,
    )
}
