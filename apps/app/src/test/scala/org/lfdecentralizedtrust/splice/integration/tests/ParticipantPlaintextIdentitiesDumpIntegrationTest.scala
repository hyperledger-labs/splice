package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.crypto.{CryptoKeyPair, Fingerprint}
import com.digitalasset.canton.topology.ParticipantId
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.util.StandaloneCanton

import java.nio.file.{Path, Paths}

class ParticipantPlaintextIdentitiesIntegrationTest
    extends ParticipantIdentitiesImportTestBase
    with StandaloneCanton {

  override def dbsSuffix = "plaintext"

  override def aliceParticipantDumpFilename =
    ParticipantPlaintextIdentitiesIntegrationTest.alicePlaintextIdentitiesDumpFilePath

  // The key encoding can change across versions even if the key stays the same so we only compare fingerprints.
  def toKeyFingerprints(dump: NodeIdentitiesDump): Seq[(Fingerprint, Option[String])] =
    dump.keys.map { key =>
      inside(key) { case NodeIdentitiesDump.NodeKey.KeyPair(bytes, name) =>
        val pair = CryptoKeyPair.fromTrustedByteString(ByteString.copyFrom(bytes.toArray)).value
        (pair.publicKey.fingerprint, name)
      }
    }

  "We can import and export Canton participant identities dumps with plaintext keys in them" in {
    implicit env =>
      startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

      val svParticipantDump = clue("Getting participant identities dump from SV1") {
        sv1ValidatorBackend.dumpParticipantIdentities()
      }

      clue("Checking exported key names for SV1") {
        val keyNames = svParticipantDump.keys.map(_.name.value)
        val prefix = "sv1Participant"
        keyNames should contain(s"$prefix-namespace")
        keyNames should contain(s"$prefix-signing")
        keyNames should contain(s"$prefix-encryption")
      }

      withCanton(
        Seq(
          testResourcesPath / "standalone-participant-extra.conf",
          testResourcesPath / "standalone-participant-extra-no-auth.conf",
        ),
        Seq(),
        "alice-plaintext-participant",
        "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
        "EXTRA_PARTICIPANT_DB" -> "participant_extra_plaintext",
      ) {
        val predefinedDump = NodeIdentitiesDump
          .fromJsonFile(
            ParticipantPlaintextIdentitiesIntegrationTest.alicePlaintextIdentitiesDumpFilePath,
            ParticipantId.tryFromProtoPrimitive,
          )
          .value

        clue("start validator with predefined dump") {
          aliceValidatorLocalBackend.startSync()
        }

        val validatorParticipantDump =
          clue("Getting participant identities dump from Alice's validator") {
            aliceValidatorLocalBackend.dumpParticipantIdentities()
          }

        clue("Checking exported keys for Alice's validator") {
          toKeyFingerprints(validatorParticipantDump).toSet shouldBe toKeyFingerprints(
            predefinedDump
          ).toSet
        }
      }
  }
}

object ParticipantPlaintextIdentitiesIntegrationTest {
  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  val alicePlaintextIdentitiesDumpFilePath =
    testDumpDir.resolve("alice-plaintext-id-identity-dump.json")
}
