package com.daml.network.integration.tests

import better.files.File

import java.nio.file.Files

class ParticipantIdentitiesExportIntegrationTest extends ParticipantIdentitiesImportTestBase {

  lazy val sv1ParticipantDumpFile: File = Files.createTempFile("sv-participant-dump", ".json")
  lazy val aliceParticipantDumpFile: File =
    Files.createTempFile("validator-participant-dump", ".json")

  override def sv1ParticipantDumpFilename = sv1ParticipantDumpFile.path
  override def aliceParticipantDumpFilename = aliceParticipantDumpFile.path

  "We can export and import Canton participant identities dumps" in { implicit env =>
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend, aliceValidatorBackend)

    val svParticipantDump = clue("Getting participant identities dump from SV1") {
      sv1ValidatorBackend.dumpParticipantIdentities()
    }

    clue("Checking exported key names") {
      val keyNames = svParticipantDump.keys.map(_.name.value)
      val prefix = "sv1Participant"
      keyNames should contain(s"$prefix-namespace")
      keyNames should contain(s"$prefix-signing")
      keyNames should contain(s"$prefix-encryption")
    }

    val validatorParticipantDump =
      clue("Getting participant identities dump from Alice's validator") {
        aliceValidatorBackend.dumpParticipantIdentities()
      }

    clue("Checking exported key names") {
      val keyNames = validatorParticipantDump.keys.map(_.name.value)
      val prefix = "aliceParticipant"
      keyNames should contain(s"$prefix-namespace")
      keyNames should contain(s"$prefix-signing")
      keyNames should contain(s"$prefix-encryption")
    }
  }
}
