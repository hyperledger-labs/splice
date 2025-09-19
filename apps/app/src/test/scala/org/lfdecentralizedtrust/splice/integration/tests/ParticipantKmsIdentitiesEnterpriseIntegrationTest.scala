package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.crypto.{EncryptionPublicKey, SigningPublicKey}
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, ParticipantBootstrapDumpConfig}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  updateAllSvAppConfigs,
  updateAllValidatorConfigs,
}
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump.NodeKey
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.StandaloneCanton

import java.nio.file.{Path, Paths}

class ParticipantKmsIdentitiesEnterpriseIntegrationTest
    extends IntegrationTest
    with StandaloneCanton {

  override protected def runEventHistorySanityCheck: Boolean = false

  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  val aliceParticipantDumpFile = testDumpDir.resolve("alice-kms-id-identity-dump.json")
  val sv2ParticipantDumpFile = testDumpDir.resolve("sv2-kms-id-identity-dump.json")

  val participantIdPrefix = "aliceValidatorNew"

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
                // comment this to generate a fresh dump with fresh keys
                participantBootstrappingDump = Some(
                  ParticipantBootstrapDumpConfig
                    .File(
                      aliceParticipantDumpFile,
                      Some(participantIdPrefix),
                    )
                ),
              )
            } else {
              c
            }
          }(conf),
        // comment this to generate a fresh dump with fresh keys
        (_, conf) => {
          updateAllSvAppConfigs { case (name, c) =>
            if (name == "sv2") {
              c.copy(
                participantBootstrappingDump = Some(
                  ParticipantBootstrapDumpConfig
                    .File(
                      sv2ParticipantDumpFile,
                      Some(s"sv2"),
                    )
                )
              )
            } else {
              c
            }
          }(conf)
        },
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

  "We can import and export Canton participant identities dumps with kms enabled (validator)" in {
    implicit env =>
      withCantonSvNodes(
        (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), None),
        "kms-identities-validator",
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
          "KMS_PROJECT_ID" -> "da-cn-splice",
          "KMS_KEY_RING_ID" -> "integration-tests",
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

        dumpMatchesParticipantState(
          predefinedDump,
          aliceValidatorBackend.participantClientWithAdminToken,
          Some(participantIdPrefix),
        )

        val dumpFromValidator = aliceValidatorBackend.dumpParticipantIdentities()
        dumpFromValidator.keys.toSet shouldBe predefinedDump.keys.toSet

        // uncomment this to write out a new dump for this test
        // better.files.File(aliceParticipantDumpFile).write(dumpFromValidator.toJson.spaces2)

        // uncomment all of this to write out new dumps for KmsMigrationDumpImportIntegrationTest
        // TODO(DACH-NY/canton-network-node#16277): Add KmsMigrationDumpImportIntegrationTest and/or remove this
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

  "We can import and export Canton participant identities dumps with kms enabled (SV)" in {
    implicit env =>
      withCantonSvNodes(
        (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), None),
        "kms-identities-sv",
        // TODO(tech-debt): Refactor so we can start only SV1 and SV2
        svs123 = true,
        sv4 = false,
        extraParticipantsConfigFileNames = Seq(
          "standalone-participant-sv2-enable-kms.conf"
        ),
      )(
        "KMS_TYPE" -> "gcp",
        "KMS_LOCATION_ID" -> "us-central1",
        "KMS_PROJECT_ID" -> "da-cn-splice",
        "KMS_KEY_RING_ID" -> "integration-tests",
      ) {
        startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

        val predefinedDump = NodeIdentitiesDump
          .fromJsonFile(
            sv2ParticipantDumpFile,
            ParticipantId.tryFromProtoPrimitive,
          )
          .value

        clue("start sv2 with predefined dump") {
          startAllSync(sv2Backend, sv2ScanBackend, sv2ValidatorBackend)
        }

        dumpMatchesParticipantState(predefinedDump, sv2Backend.participantClientWithAdminToken)

        val dumpFromSvValidator = sv2ValidatorBackend.dumpParticipantIdentities()
        dumpFromSvValidator.keys.toSet shouldBe predefinedDump.keys.toSet

        // uncomment this to write out a new dump for this test
        // better.files.File(sv2ParticipantDumpFile).write(dumpFromSvValidator.toJson.spaces2)
      }
  }

  private def dumpMatchesParticipantState(
      dump: NodeIdentitiesDump,
      participant: ParticipantClientReference,
      prefixOverwrite: Option[String] = None,
  ) = {
    clue("Participant ID is the same") {
      participant.id shouldBe ParticipantId(
        dump.id.uid.tryChangeId(
          prefixOverwrite.getOrElse(dump.id.uid.toProtoPrimitive.split("::")(0))
        )
      )
    }
    clue("keys are correctly registered") {
      val keys = participant.keys.secret.list()
      keys should have size dump.keys.size.toLong

      def checkKmsKeyId(keyName: String) = {
        val key = keys.find(_.name.exists(_.unwrap == keyName)).value
        inside(dump.keys.find(_.name.value == key.name.value.unwrap).value) {
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
  }
}
