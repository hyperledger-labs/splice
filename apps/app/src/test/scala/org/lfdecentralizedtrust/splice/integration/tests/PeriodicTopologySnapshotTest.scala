package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Authorized
import com.google.cloud.storage.Blob
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.config.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.GcpBucket
import org.slf4j.event.Level

import java.nio.charset.StandardCharsets
import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.DurationInt

abstract class PeriodicTopologySnapshotIntegrationTestBase[T <: BackupDumpConfig]
    extends IntegrationTest {

  protected val topologySnapshotInterval: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.ofMinutes(10)

  protected def topologySnapshotConfig: PeriodicBackupDumpConfig =
    PeriodicBackupDumpConfig(topologySnapshotLocation, topologySnapshotInterval)

  protected def topologySnapshotLocation: T

  protected def listDump(filenamePrefix: String): Seq[Blob]

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

      val onboardingState = clue("the 3 files created from the topology snapshot exist in gcp.")({
        val dumps = listDump(s"topology_snapshot_$utcDate").filter(_.getSize > 0L)
        dumps.size shouldBe 3
        new String(
          dumps
            .find(_.getName.endsWith("metadata"))
            .getOrElse(throw new RuntimeException("Metadata file not found."))
            .getContent(),
          StandardCharsets.UTF_8,
        ) should include("SEQ::sv1") // the sequencerId change through ci runs
        dumps.exists(_.getName.endsWith("authorized")) shouldBe true
        dumps
          .find(_.getName.endsWith("onboarding-state"))
          .getOrElse(throw new RuntimeException("Onboarding state dump not found."))
          .getContent()
      })

      clue("the topology snapshot import works.")({
        sv1Backend.appState.localSynchronizerNode.value.sequencerAdminConnection
          .importTopologySnapshot(
            ByteString.copyFrom(onboardingState),
            Authorized,
          )
      })
    }
  }
}

final class GcpBucketPeriodicTopologySnapshotIntegrationTest
    extends PeriodicTopologySnapshotIntegrationTestBase[BackupDumpConfig.Gcp] {
  override def topologySnapshotLocation: BackupDumpConfig.Gcp =
    BackupDumpConfig.Gcp(GcpBucketConfig.inferForTesting(TopologySnapshotTest), None)
  val bucket = new GcpBucket(topologySnapshotLocation.bucket, loggerFactory)
  override def listDump(filenamePrefix: String): Seq[Blob] = {
    bucket.list(startOffset = filenamePrefix, endOffset = "")
  }
}
