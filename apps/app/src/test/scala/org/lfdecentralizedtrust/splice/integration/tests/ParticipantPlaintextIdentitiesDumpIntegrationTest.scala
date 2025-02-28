package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File
import com.digitalasset.canton.crypto.{CryptoKeyPair, Fingerprint}
import com.digitalasset.canton.topology.ParticipantId
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, ParticipantBootstrapDumpConfig}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ensureNovelDamlNames,
  selfSignedTokenAuthSourceTransform,
  updateAllScanAppConfigs,
  updateAllSvAppConfigs,
  updateAllValidatorConfigs,
}
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.StandaloneCanton
import monocle.macros.syntax.lens.*

import java.nio.file.{Path, Paths}

class ParticipantPlaintextIdentitiesIntegrationTest extends IntegrationTest with StandaloneCanton {

  val svNodePath: File = testResourcesPath / "local-sv-node"
  val validatorNodePath: File = testResourcesPath / "local-validator-node"

  val svParticipantPath: File = svNodePath / "canton-participant"
  val svDomainPath: File = svNodePath / "canton-domain"
  val svAppPath: File = svNodePath / "sv-app"
  val scanAppPath: File = svNodePath / "scan-app"
  val svValidatorAppPath: File = svNodePath / "validator-app"

  val validatorAppPath: File = validatorNodePath / "validator-app"

  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  val aliceParticipantDumpFile = testDumpDir.resolve("alice-plaintext-id-identity-dump.json")

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        // Config that runs against long-running Canton; sv1 defined here
        testResourcesPath / "simple-topology.conf",
        // Config that runs against Canton started from this test; sv1-local defined here
        svAppPath / "app.conf",
        scanAppPath / "app.conf",
        svValidatorAppPath / "app.conf",
        validatorAppPath / "app.conf",
      )
      .clearConfigTransforms()
      .addConfigTransforms(
        (_, config) => ensureNovelDamlNames()(config),
        (_, config) => ConfigTransforms.withPausedSvDomainComponentsOffboardingTriggers()(config),
        (_, config) => useSelfSignedTokensForLongRunningLedgerApiAuth("test", config),
        (_, config) =>
          updateAllValidatorConfigs { case (name, c) =>
            if (name == "aliceValidatorLocal") {
              val randomParticipantSuffix =
                (new scala.util.Random).nextInt().toHexString.toLowerCase
              c.copy(
                participantBootstrappingDump = Some(
                  ParticipantBootstrapDumpConfig
                    .File(
                      aliceParticipantDumpFile,
                      Some(s"aliceValidatorLocal-$randomParticipantSuffix"),
                    )
                )
              )
            } else {
              c
            }
          }(config),
        // A short polling interval is required by UpdateHistorySanityCheckPlugin
        (_, config) => ConfigTransforms.reducePollingInterval(config),
      )
      .withAllocatedUsers()
      .withManualStart

  override def dbsSuffix = "identities_plaintext"

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
        "EXTRA_PARTICIPANT_DB" -> "participant_extra_identities_plaintext",
      ) {
        val predefinedDump = NodeIdentitiesDump
          .fromJsonFile(
            aliceParticipantDumpFile,
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

  // TODO(tech-debt) Consider removing this method in favor of making `useSelfSignedTokensForLedgerApiAuth` take an `ignore` parameter
  private def useSelfSignedTokensForLongRunningLedgerApiAuth(
      secret: String,
      config: SpliceConfig,
  ): SpliceConfig = {
    val enableAuth =
      selfSignedTokenAuthSourceTransform(config.parameters.clock, secret)
    val transforms = Seq(
      updateAllSvAppConfigs((name, c) =>
        if (name.endsWith("Local")) {
          c
        } else {
          c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
        }
      ),
      updateAllValidatorConfigs((name, c) => {
        if (name.endsWith("Local")) {
          c
        } else {
          c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
        }
      }),
      updateAllScanAppConfigs((name, c) => {
        if (name.endsWith("Local")) {
          c
        } else {
          c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.svUser, _))
        }
      }),
    )
    transforms.foldLeft(config)((c, tf) => tf(c))
  }
}
