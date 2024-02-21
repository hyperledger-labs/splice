package com.daml.network.integration.tests

import com.daml.network.config.{BackupDumpConfig, PeriodicBackupDumpConfig}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.digitalasset.canton.config.NonNegativeFiniteDuration

final class CombinedDumpDirectoryExportTimeBasedIntegrationTest
    extends AcsStoreDumpExportTimeBasedIntegrationTestBase {
  override def acsStoreDumpConfig(testContext: String) =
    PeriodicBackupDumpConfig(
      location = BackupDumpConfig.Directory(
        AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpOutputDir
      ),
      NonNegativeFiniteDuration.ofMinutes(10),
    )

  override def readDump(filename: String) = {
    import better.files.File
    val dumpFile =
      File(AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpOutputDir) / filename
    dumpFile.contentAsString
  }

  // we write out participant identities dumps manually
  // because it give us more convenient control over file name and storage location
  def writeParticipantDump(nodeName: String, content: String)(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val now = env.environment.clock.now
    val filename = s"${nodeName}_participant_dump_${now}.json"
    import better.files.File
    val dumpFile =
      File(AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpOutputDir) / filename
    dumpFile.overwrite(content)
  }

  "sv1" should {
    "produce a participant identities dump via a download from the ValidatorApp admin api" in {
      implicit env =>
        val svParticipantDump = clue("Getting participant identities dump from SV1") {
          sv1ValidatorBackend.dumpParticipantIdentities()
        }
        writeParticipantDump("sv1", svParticipantDump.toJson.spaces2)
    }
  }
  "alice" should {
    "produce a participant identities dump via a download from the ValidatorApp admin api" in {
      implicit env =>
        val validatorParticipantDump =
          clue("Getting participant identities dump from Alice's validator") {
            aliceValidatorBackend.dumpParticipantIdentities()
          }
        writeParticipantDump("alice", validatorParticipantDump.toJson.spaces2)
    }
  }
}
