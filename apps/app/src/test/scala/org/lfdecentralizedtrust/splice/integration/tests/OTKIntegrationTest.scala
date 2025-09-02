package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllSvAppConfigs
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, ParticipantBootstrapDumpConfig}
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.StandaloneCanton

import java.nio.file.{Path, Paths}

class OTKIntegrationTest extends IntegrationTest with StandaloneCanton {

  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  val sv2ParticipantDumpFile: Path = testDumpDir.resolve("sv2-plaintext-id-identity-dump.json")

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(Seq("simple-topology.conf"), this.getClass.getSimpleName)
      .clearConfigTransforms() // mainly to get static daml names
      .addConfigTransforms(
        (_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => ConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
        // comment this to generate a fresh dump with fresh keys
        (_, conf) => {
          updateAllSvAppConfigs { case (name, c) =>
            if (name == "sv2") {
              c.copy(
                participantBootstrappingDump = Some(
                  ParticipantBootstrapDumpConfig
                    .File(
                      sv2ParticipantDumpFile,
                      Some("sv2"),
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

  override def dbsSuffix = "identities_otk2"

  "We can import and export Canton participant identities dumps (SV)" in { implicit env =>
    withCantonSvNodes(
      (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), None),
      "otk2-identities-sv",
      // TODO(tech-debt): Refactor so we can start only SV1 and SV2
      sv4 = false,
    )() {
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
    }
  }
}
