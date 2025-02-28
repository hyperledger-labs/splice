package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.crypto.{EncryptionPublicKey, SigningPublicKey}
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, ParticipantBootstrapDumpConfig}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllValidatorConfigs
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump.NodeKey
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.StandaloneCanton
import org.scalatest.Ignore

import java.nio.file.{Path, Paths}

// TODO(#17677) Reenable once Canton fixes their compatibility issues
@Ignore
class ParticipantKmsIdentitiesIntegrationTest extends IntegrationTest with StandaloneCanton {

  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  val aliceParticipantDumpFile = testDumpDir.resolve("alice-kms-id-identity-dump.json")

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(Seq("simple-topology.conf"), this.getClass.getSimpleName)
      .clearConfigTransforms() // mainly to get static daml names
      .addConfigTransforms(
        (_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => ConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
        (_, conf) =>
          updateAllValidatorConfigs { case (name, c) =>
            if (name == "aliceValidator") {
              c.copy(
                domains = c.domains.copy(extra = Seq.empty),
                participantBootstrappingDump = Some(
                  ParticipantBootstrapDumpConfig
                    .File(
                      aliceParticipantDumpFile,
                      Some(s"aliceValidator"),
                    )
                ),
              )
            } else {
              c
            }
          }(conf),
        // default transforms that look relevant
        (_, config) => ConfigTransforms.makeAllTimeoutsBounded(config),
        (_, config) => ConfigTransforms.useSelfSignedTokensForLedgerApiAuth("test")(config),
        (_, config) => ConfigTransforms.reducePollingInterval(config),
        (_, config) => ConfigTransforms.withPausedSvDomainComponentsOffboardingTriggers()(config),
        (_, config) => ConfigTransforms.disableOnboardingParticipantPromotionDelay()(config),
      )
      .withManualStart

  override lazy val resetRequiredTopologyState: Boolean = false

  override def dbsSuffix = "identities_kms"

  "We can import and export Canton participant identities dumps with kms enabled" in {
    implicit env =>
      withCantonSvNodes(
        (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), None),
        "kms-identities",
        // TODO(tech-debt): Refactor so we can start only SV1 nodes
        svs123 = true,
        sv4 = false,
        extraParticipantsConfigFileNames = Seq(
          "standalone-participant-extra.conf",
          "standalone-participant-extra-no-auth.conf",
          "standalone-participant-extra-enable-kms.conf",
        ),
        extraParticipantsEnvMap = Map(
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> ("participant_extra_" + dbsSuffix),
          "KMS_TYPE" -> "gcp",
          "KMS_LOCATION_ID" -> "us-central1",
          "KMS_PROJECT_ID" -> "da-cn-shared",
          "KMS_KEY_RING_ID" -> "kms-ci",
        ),
      )() {
        startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

        val predefinedDump = NodeIdentitiesDump
          .fromJsonFile(
            aliceParticipantDumpFile,
            ParticipantId.tryFromProtoPrimitive,
          )
          .value

        clue("start validator with predefined dump") {
          aliceValidatorBackend.startSync()
        }

        clue("keys are correctly registered") {
          val keys =
            aliceValidatorBackend.participantClientWithAdminToken.keys.secret
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

        clue("Participant ID is the same") {
          aliceValidatorBackend.participantClientWithAdminToken.id shouldBe predefinedDump.id
        }

        val dumpFromValidator = aliceValidatorBackend.dumpParticipantIdentities()
        dumpFromValidator.keys.toSet shouldBe predefinedDump.keys.toSet

        // uncomment this to write out a new dump for this test
        // better.files.File(aliceParticipantDumpFile).write(dumpFromValidator.toJson.spaces2)

        // uncomment all of this to write out new dumps for KmsMigrationDumpImportIntegrationTest
        // TODO(#16277): Add KmsMigrationDumpImportIntegrationTest and/or remove this
        // import io.circe.syntax.EncoderOps
        // val someTimestamp = java.time.Instant.now()
        // // sv1
        // val idsFromSv1 = sv1Backend.getSynchronizerNodeIdentitiesDump()
        // val snapshotFromSv1 =
        //   eventually()(sv1Backend.getDomainDataSnapshot(someTimestamp, force = true))
        // val fullDumpFromSv1 = org.lfdecentralizedtrust.splice.sv.migration.DomainMigrationDump(
        //   migrationId = snapshotFromSv1.migrationId,
        //   idsFromSv1,
        //   snapshotFromSv1.dataSnapshot,
        //   createdAt = snapshotFromSv1.createdAt,
        // )
        // val sv1MigrationDumpPath = testDumpDir.resolve(s"sv1-plaintext-migration-dump.json")
        // better.files.File(sv1MigrationDumpPath).write(fullDumpFromSv1.asJson.spaces2)
        // // alice
        // val fullDumpFromValidator = eventually()(
        //   aliceValidatorBackend.getValidatorDomainDataSnapshot(
        //     someTimestamp.toString,
        //     force = true,
        //   )
        // )
        // val aliceMigrationDumpPath = testDumpDir.resolve(s"alice-kms-migration-dump.json")
        // better.files.File(aliceMigrationDumpPath).write(fullDumpFromValidator.asJson.spaces2)
      }
  }
}
