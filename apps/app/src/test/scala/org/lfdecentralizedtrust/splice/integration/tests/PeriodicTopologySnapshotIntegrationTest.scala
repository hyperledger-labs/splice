package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.google.cloud.storage.Blob
import org.lfdecentralizedtrust.splice.config.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.GcpBucket
import org.slf4j.event.Level

import java.nio.charset.StandardCharsets
import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.DurationInt

class PeriodicTopologySnapshotIntegrationTest[T <: BackupDumpConfig] extends IntegrationTest {

  private val bucket = new GcpBucket(topologySnapshotLocation.bucket, loggerFactory)

  private val topologySnapshotInterval: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.ofMinutes(10)

  private def topologySnapshotConfig: PeriodicBackupDumpConfig =
    PeriodicBackupDumpConfig(topologySnapshotLocation, topologySnapshotInterval)

  private def topologySnapshotLocation: BackupDumpConfig.Gcp =
    BackupDumpConfig.Gcp(GcpBucketConfig.inferForTesting(TopologySnapshotTest), None)

  private def listDump(utcDate: String): Seq[Blob] =
    bucket.list(prefix = s"topology_snapshot_$utcDate")

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransformsToFront((_, conf) =>
        ConfigTransforms.updateAllSvAppConfigs((_, c) =>
          c.copy(topologySnapshotConfig = Some(topologySnapshotConfig))
        )(conf)
      )
      .withManualStart

  "sv1" should {
    "produces a topology snapshot in the background" in { implicit env =>
      val utcDate = ZonedDateTime.now(ZoneOffset.UTC).toLocalDate.toString
      clue("topology snapshot is being produced")(
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
          timeUntilSuccess = 40.seconds,
          within = {
            initDsoWithSv1Only()
          },
          assertion = logEntries => {
            forAtLeast(
              1,
              logEntries,
            )(logEntry =>
              logEntry.message should (include(s"Took a new topology snapshot on $utcDate") or
                include("Today's topology snapshot already exists."))
            )
          },
        )
      )

      clue("the 3 files created from the topology snapshot exist in gcp.")({
        val dumps = listDump(utcDate).filter(_.getSize > 0L)
        dumps.size shouldBe 3
        new String(
          dumps
            .find(_.getName.endsWith("metadata"))
            .getOrElse(throw new RuntimeException("Metadata file not found."))
            .getContent(),
          StandardCharsets.UTF_8,
        ) should include("SEQ::sv1") // the sequencerId change through ci runs
        dumps.exists(_.getName.endsWith("authorized")) shouldBe true
        dumps.exists(_.getName.endsWith("onboarding-state")) shouldBe true
      })
    }
  }
}
