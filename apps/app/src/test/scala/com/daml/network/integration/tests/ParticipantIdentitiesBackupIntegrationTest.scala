package com.daml.network.integration.tests

import com.daml.network.config.{BackupDumpConfig, CNNodeConfigTransforms, GcpBucketConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{GcpBucket, ParticipantIdentitiesDump}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import java.nio.file.Paths

abstract class ParticipantIdentitiesBackupIntegrationTestBase[T <: BackupDumpConfig]
    extends CNNodeIntegrationTest {

  protected val backupInterval: Option[NonNegativeFiniteDuration] = Some(
    NonNegativeFiniteDuration.ofMinutes(10)
  )

  protected def backupDumpConfig: T

  protected def readDump(filename: String): String

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformsToFront(
        CNNodeConfigTransforms.onlySv1,
        (_, conf) =>
          CNNodeConfigTransforms.updateAllValidatorAppConfigs_(c =>
            c.copy(participantIdentitiesBackup = Some(backupDumpConfig))
          )(conf),
      )
      .withManualStart

  "alice's validator" should {
    "produce a participant identities dump in the background" in { implicit env =>
      initSvcWithSv1Only()
      val logLineRegex =
        "Wrote participant identities dump.*at path: (.*)/(.*\\.json)".r

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        aliceValidatorBackend.startSync(),
        logEntries => {
          forAtLeast(
            1,
            logEntries,
          )(logEntry => {
            inside(logEntry.message) { case logLineRegex(subDir, filename) =>
              val dump = readDump(Paths.get(subDir, filename).toString)
              val jsonDump = ParticipantIdentitiesDump
                .fromJsonString(dump)
                .fold(
                  err =>
                    throw new IllegalArgumentException(
                      s"Failed to parse dump: $err from $filename"
                    ),
                  result => result,
                )
              jsonDump.id.toProtoPrimitive should startWith("PAR::aliceParticipant")
            }
          })
        },
      )
    }
  }
}

final class DirectoryParticipantIdentitiesBackupIntegrationTest
    extends ParticipantIdentitiesBackupIntegrationTestBase[BackupDumpConfig.Directory] {
  override def backupDumpConfig =
    BackupDumpConfig.Directory(Paths.get("dumps/testing"), backupInterval)

  override def readDump(filename: String) = {
    import better.files.File
    val dumpDir = File(backupDumpConfig.directory)
    val dumpFile = dumpDir / filename
    dumpFile.contentAsString
  }

}

final class GcpBucketParticipantIdentitiesBackupIntegrationTest
    extends ParticipantIdentitiesBackupIntegrationTestBase[BackupDumpConfig.Gcp] {
  override def backupDumpConfig =
    BackupDumpConfig.Gcp(GcpBucketConfig.inferForTesting, None, backupInterval)
  val bucket = new GcpBucket(backupDumpConfig.bucket, loggerFactory)
  override def readDump(filename: String) = {
    bucket.readStringFromBucket(Paths.get(filename))
  }
}
