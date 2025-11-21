package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.config.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.GcpBucket
import org.slf4j.event.Level

import java.nio.file.Paths
import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.DurationInt

abstract class PeriodicTopologySnapshotIntegrationTestBase[T <: BackupDumpConfig]
    extends IntegrationTest {

  protected val topologySnapshotInterval: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.ofMinutes(10)

  protected def topologySnapshotConfig: PeriodicBackupDumpConfig =
    PeriodicBackupDumpConfig(topologySnapshotLocation, topologySnapshotInterval)

  protected def topologySnapshotLocation: T

  protected def readDump(filename: String): String

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
    }
  }
}

final class GcpBucketPeriodicTopologySnapshotIntegrationTest
    extends PeriodicTopologySnapshotIntegrationTestBase[BackupDumpConfig.Gcp] {
  override def topologySnapshotLocation: BackupDumpConfig.Gcp =
    BackupDumpConfig.Gcp(GcpBucketConfig.inferForTesting(TopologySnapshotTest), None)
  val bucket = new GcpBucket(topologySnapshotLocation.bucket, loggerFactory)
  override def readDump(filename: String): String = {
    bucket.readStringFromBucket(Paths.get(filename))
  }
}
