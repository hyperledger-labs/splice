package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.{
  BackupDumpConfig,
  ConfigTransforms,
  GcpBucketConfig,
  IdentityDumpTest,
  PeriodicBackupDumpConfig,
}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.GcpBucket
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.ParticipantId
import org.slf4j.event.Level

import java.nio.file.{Path, Paths}

abstract class PeriodicBackupIntegrationTestBase[T <: BackupDumpConfig] extends IntegrationTest {

  protected val backupInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10)

  protected def backupDumpConfig = PeriodicBackupDumpConfig(backupDumpLocation, backupInterval)

  protected def backupDumpLocation: T

  protected def readDump(filename: String): String

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformsToFront((_, conf) =>
        ConfigTransforms.updateAllValidatorAppConfigs_(c =>
          c.copy(participantIdentitiesBackup = Some(backupDumpConfig))
        )(conf)
      )
      .withManualStart

  "sv1 and alice's validator" should {
    "produce backup dumps in the background" in { implicit env =>
      initDsoWithSv1Only()
      val participantIdentitiesLogLineRegex =
        "Wrote node identities dump.*at path: (.*\\.json)".r
      clue("start alice's validator and observe participant identities dump being produced ")(
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
          aliceValidatorBackend.startSync(),
          logEntries => {
            forAtLeast(
              1,
              logEntries,
            )(logEntry => {
              inside((logEntry.loggerName, logEntry.message)) {
                case (name, participantIdentitiesLogLineRegex(filename))
                    if name.endsWith("validator=aliceValidator") =>
                  val dump = readDump(filename)
                  val jsonDump = NodeIdentitiesDump
                    .fromJsonString(ParticipantId.tryFromProtoPrimitive, dump)
                    .fold(
                      err =>
                        throw new IllegalArgumentException(
                          s"Failed to parse dump: $err from $filename"
                        ),
                      result => result,
                    )
                  jsonDump.id.toProtoPrimitive should startWith("PAR::aliceValidator")
              }
            })
          },
        )
      )
    }
  }
}

final class DirectoryPeriodicBackupIntegrationTest
    extends PeriodicBackupIntegrationTestBase[BackupDumpConfig.Directory] {

  private val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")

  // Not using temp-files so test-generated outputs are easy to inspect.
  private val testDumpOutputDir: Path = testDumpDir.resolve("test-outputs")

  override def backupDumpLocation = {
    BackupDumpConfig.Directory(testDumpOutputDir)
  }

  override def readDump(filename: String) = {
    import better.files.File
    val dumpDir = File(backupDumpLocation.directory)
    val dumpFile = dumpDir / filename
    dumpFile.contentAsString
  }

}

final class GcpBucketPeriodicBackupIntegrationTest
    extends PeriodicBackupIntegrationTestBase[BackupDumpConfig.Gcp] {
  override def backupDumpLocation =
    BackupDumpConfig.Gcp(GcpBucketConfig.inferForTesting(IdentityDumpTest), None)
  val bucket = new GcpBucket(backupDumpLocation.bucket, loggerFactory)
  override def readDump(filename: String) = {
    bucket.readStringFromBucket(Paths.get(filename))
  }
}
