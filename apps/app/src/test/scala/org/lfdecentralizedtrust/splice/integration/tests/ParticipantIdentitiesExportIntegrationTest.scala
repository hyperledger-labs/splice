package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.crypto.{EncryptionPublicKey, SigningPublicKey}
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump.NodeKey
import org.lfdecentralizedtrust.splice.util.StandaloneCanton

import java.nio.file.{Path, Paths}

class ParticipantIdentitiesExportIntegrationTest
    extends ParticipantIdentitiesImportTestBase
    with StandaloneCanton {

  override def dbsSuffix = "kms"

  override def aliceParticipantDumpFilename =
    ParticipantIdentitiesExportIntegrationTest.aliceKmsIdIdentityDumpFilePath

  "We can export Canton participant identities dumps" in { implicit env =>
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

  "We can export and import Canton participant identities dumps with kms enabled" in {
    implicit env =>
      startAllSync(
        sv1Backend,
        sv1ScanBackend,
        sv1ValidatorBackend,
      )

      withCanton(
        Seq(
          testResourcesPath / "standalone-participant-extra.conf",
          testResourcesPath / "standalone-participant-extra-no-auth.conf",
          testResourcesPath / "standalone-participant-extra-enable-kms.conf",
        ),
        Seq(),
        "alice-kms-participant",
        "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
        "EXTRA_PARTICIPANT_DB" -> "participant_extra_kms",
        "KMS_TYPE" -> "gcp",
        "KMS_LOCATION_ID" -> "us-central1",
        "KMS_PROJECT_ID" -> "da-cn-shared",
        "KMS_KEY_RING_ID" -> "kms-ci",
      ) {
        val predefinedDump = NodeIdentitiesDump
          .fromJsonFile(
            ParticipantIdentitiesExportIntegrationTest.aliceKmsIdIdentityDumpFilePath,
            ParticipantId.tryFromProtoPrimitive,
          )
          .value

        clue("start validator with predefined dump") {
          aliceValidatorLocalBackend.startSync()
        }

        clue("keys are correctly registered") {
          val keys =
            aliceValidatorLocalBackend.participantClientWithAdminToken.keys.secret
              .list()
          keys should have size predefinedDump.keys.size.toLong

          def checkKmsKeyId(keyName: String) = {
            val key = keys.find(_.name.exists(_.unwrap == keyName)).value
            inside(predefinedDump.keys.find(_.name.value == key.name.value.unwrap).value) {
              case NodeKey.KmsKeyId(keyType, keyId, _) =>
                key.kmsKeyId.value.unwrap shouldBe keyId
                key.publicKey match {
                  case _: SigningPublicKey => keyType shouldBe NodeKey.KeyType.Signing
                  case _: EncryptionPublicKey => keyType shouldBe NodeKey.KeyType.Encryption
                  case _ => fail("Unexpected key type")
                }
            }
          }

          checkKmsKeyId("namespace")
          checkKmsKeyId("signing")
          checkKmsKeyId("encryption")
        }

        val dumpFromValidator = aliceValidatorLocalBackend.dumpParticipantIdentities()
        dumpFromValidator.keys.toSet shouldBe predefinedDump.keys.toSet
      }
  }
}

object ParticipantIdentitiesExportIntegrationTest {
  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  val aliceKmsIdIdentityDumpFilePath = testDumpDir.resolve("alice-kms-id-identity-dump.json")
}
