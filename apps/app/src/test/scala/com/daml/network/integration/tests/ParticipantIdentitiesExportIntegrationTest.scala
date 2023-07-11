package com.daml.network.integration.tests

import better.files.File
import com.daml.network.util.ParticipantIdentitiesDump

import java.nio.file.Files

class ParticipantIdentitiesExportIntegrationTest extends ParticipantIdentitiesImportTestBase {

  lazy val sv1ParticipantDumpFile: File = Files.createTempFile("sv-participant-dump", ".json")
  lazy val aliceParticipantDumpFile: File =
    Files.createTempFile("validator-participant-dump", ".json")

  override def sv1ParticipantDumpFilename = sv1ParticipantDumpFile.path
  override def aliceParticipantDumpFilename = aliceParticipantDumpFile.path

  "We can export and import Canton participant identities dumps" in { implicit env =>
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend, aliceValidatorBackend)

    val svcInfoBefore = sv1Backend.getSvcInfo()
    val validatorPartyBefore = aliceValidatorBackend.getValidatorPartyId()

    val svParticipantDump = clue("Getting participant identities dump from SV1") {
      sv1ValidatorBackend.dumpParticipantIdentities()
    }
    clue("Checking exported users list") {
      val sv1Party = svcInfoBefore.svParty
      svParticipantDump.users should contain(
        ParticipantIdentitiesDump.ParticipantUser(sv1Backend.config.ledgerApiUser, Some(sv1Party))
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
    val validatorParticipantDump =
      clue("Getting participant identities dump from Alice's validator") {
        aliceValidatorBackend.dumpParticipantIdentities()
      }
    clue("Checking exported users list") {
      validatorParticipantDump.users should contain(
        ParticipantIdentitiesDump.ParticipantUser(
          aliceValidatorBackend.config.ledgerApiUser,
          Some(validatorPartyBefore),
        )
      )
    }
  }
}
