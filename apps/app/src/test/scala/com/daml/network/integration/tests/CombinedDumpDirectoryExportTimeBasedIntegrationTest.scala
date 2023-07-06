package com.daml.network.integration.tests

import com.daml.network.config.BackupDumpConfig
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.ParticipantIdentitiesDump

final class CombinedDumpDirectoryExportTimeBasedIntegrationTest
    extends AcsStoreDumpTriggerExportTimeBasedIntegrationTestBase[BackupDumpConfig.Directory] {
  override def acsStoreDumpConfig(testContext: String) =
    BackupDumpConfig.Directory(
      AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpOutputDir,
      None,
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
        val sv1Party = sv1Backend.getSvcInfo().svParty
        clue("Checking exported users list") {
          svParticipantDump.users should contain(
            ParticipantIdentitiesDump.ParticipantUser(
              sv1Backend.config.ledgerApiUser,
              Some(sv1Party),
            )
          )
          svParticipantDump.users should contain(
            ParticipantIdentitiesDump.ParticipantUser(
              sv1ValidatorBackend.config.ledgerApiUser,
              Some(sv1Party),
            )
          )
          svParticipantDump.users should contain(
            ParticipantIdentitiesDump.ParticipantUser(
              sv1ValidatorBackend.config.validatorWalletUser.value,
              Some(sv1Party),
            )
          )
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
        val validatorParty = aliceValidatorBackend.getValidatorPartyId()
        clue("Checking exported users list") {
          validatorParticipantDump.users should contain(
            ParticipantIdentitiesDump.ParticipantUser(
              aliceValidatorBackend.config.ledgerApiUser,
              Some(validatorParty),
            )
          )
        }
        writeParticipantDump("alice", validatorParticipantDump.toJson.spaces2)
    }
  }
}
