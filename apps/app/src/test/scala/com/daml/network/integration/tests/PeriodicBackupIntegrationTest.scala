package com.daml.network.integration.tests

import com.daml.network.config.{BackupDumpConfig, CNNodeConfigTransforms, GcpBucketConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions as http
import com.daml.network.identities.NodeIdentitiesDump
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.GcpBucket
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.ParticipantId
import org.slf4j.event.Level

import java.nio.file.Paths

abstract class PeriodicBackupIntegrationTestBase[T <: BackupDumpConfig]
    extends CNNodeIntegrationTest {

  protected val backupInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10)

  protected def backupDumpConfig: T

  protected def readDump(filename: String): String

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformsToFront(
        (_, conf) =>
          CNNodeConfigTransforms.updateAllValidatorAppConfigs_(c =>
            c.copy(participantIdentitiesBackup = Some(backupDumpConfig))
          )(conf),
        (_, conf) =>
          CNNodeConfigTransforms.updateAllSvAppConfigs_(c =>
            c.copy(acsStoreDump = Some(backupDumpConfig))
          )(conf),
      )
      .withManualStart

  "founding SV and alice's validator" should {
    val acsDumpLogLineRegex =
      "Wrote ACS store dump.*at path: (.*\\.json)".r

    "produce backup dumps in the background" in { implicit env =>
      clue("start SvApp for SV1 and observe acs store dump being produced") {
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
          initSvcWithSv1Only(),
          logEntries => {
            forAtLeast(
              1,
              logEntries,
            )(logEntry => {
              inside((logEntry.loggerName, logEntry.message)) {
                case (name, acsDumpLogLineRegex(filename)) if name.endsWith("SV=sv1") =>
                  val dump = readDump(filename)
                  io.circe.parser
                    .decode[http.GetAcsStoreDumpResponse](dump)
                    .fold(
                      err =>
                        throw new IllegalArgumentException(
                          s"Failed to parse dump: $err from $filename"
                        ),
                      result => result,
                    )
                  // No assertion apart from parsing the dump, as it is typically empty due to getting triggered as
                  // soon as the store ingested the ACS.
                  succeed
              }
            })
          },
        )
      }

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
                  jsonDump.id.toProtoPrimitive should startWith("PAR::aliceParticipant")
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

  override def backupDumpConfig =
    BackupDumpConfig.Directory(
      AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpOutputDir,
      backupInterval,
    )

  override def readDump(filename: String) = {
    import better.files.File
    val dumpDir = File(backupDumpConfig.directory)
    val dumpFile = dumpDir / filename
    dumpFile.contentAsString
  }

}

final class GcpBucketPeriodicBackupIntegrationTest
    extends PeriodicBackupIntegrationTestBase[BackupDumpConfig.Gcp] {
  override def backupDumpConfig =
    BackupDumpConfig.Gcp(GcpBucketConfig.inferForTesting, None, backupInterval)
  val bucket = new GcpBucket(backupDumpConfig.bucket, loggerFactory)
  override def readDump(filename: String) = {
    bucket.readStringFromBucket(Paths.get(filename))
  }
}
