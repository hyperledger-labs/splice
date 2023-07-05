package com.daml.network.integration.tests

import better.files.{File, *}
import com.daml.network.config.{
  CNNodeConfig,
  CNNodeConfigTransforms,
  ParticipantBootstrapDumpConfig,
}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{ParticipantIdentitiesDump, ProcessTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

import java.nio.file.Files
import scala.util.Using

class ParticipantExportImportIntegrationTest extends CNNodeIntegrationTest with ProcessTestUtil {

  val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources"
  val svNodePath: File = testResourcesPath / "local-sv-node"
  val validatorNodePath: File = testResourcesPath / "local-validator-node"

  val svParticipantPath: File = svNodePath / "canton-participant"
  val svDomainPath: File = svNodePath / "canton-domain"
  val svAppPath: File = svNodePath / "sv-app"
  val scanAppPath: File = svNodePath / "scan-app"

  val validatorParticipantPath: File = validatorNodePath / "canton-participant"
  val validatorAppPath: File = validatorNodePath / "validator-app"

  lazy val svDumpFile: File = Files.createTempFile("sv-participant-dump", ".json")
  lazy val validatorDumpFile: File = Files.createTempFile("validator-participant-dump", ".json")

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] = {
    CNNodeEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        // Config that runs against long-running Canton; sv1 defined here
        testResourcesPath / "simple-topology.conf",
        // Config that runs against Canton started from this test; sv1-local defined here
        svAppPath / "app.conf",
        scanAppPath / "app.conf",
        validatorAppPath / "app.conf",
      )
      .clearConfigTransforms()
      .addConfigTransforms(
        (_, config) => CNNodeConfigTransforms.ensureNovelDamlNames()(config),
        (_, config) => useSelfSignedTokensForLongRunningLedgerApiAuth("test", config),
        (_, config) =>
          CNNodeConfigTransforms.updateAllSvAppConfigs { case (name, c) =>
            if (name == "sv1Local") {
              c.copy(participantBootstrappingDump =
                Some(ParticipantBootstrapDumpConfig.File(svDumpFile.path))
              )
            } else {
              c
            }
          }(config),
        (_, config) =>
          CNNodeConfigTransforms.updateAllValidatorConfigs { case (name, c) =>
            if (name == "aliceValidatorLocal") {
              c.copy(participantBootstrappingDump =
                Some(ParticipantBootstrapDumpConfig.File(validatorDumpFile.path))
              )
            } else {
              c
            }
          }(config),
      )
      .withAllocatedUsers()
      .withManualStart
  }

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
    clue("Writing dumps to file") {
      svDumpFile.overwrite(svParticipantDump.toJson.spaces2)
      validatorDumpFile.overwrite(validatorParticipantDump.toJson.spaces2)
    }
    clue("Stopping nodes before reset") {
      aliceValidatorBackend.stop()
      sv1ValidatorBackend.stop()
      sv1ScanBackend.stop()
      sv1Backend.stop()
    }

    Using.resource(startStandaloneCanton()(env)) { _ =>
      clue("Starting nodes after reset") {
        startAllSync(sv1LocalBackend, sv1ScanLocalBackend, aliceValidatorLocalBackend)
      }
      clue("Checking that SV identities were transferred correctly") {
        val svcInfoAfter = sv1LocalBackend.getSvcInfo()
        svcInfoAfter.svParty shouldBe svcInfoBefore.svParty
        svcInfoAfter.svcParty shouldBe svcInfoBefore.svcParty
      }
      clue("Checking that validator identities were transferred correctly") {
        val aliceValidatorPartyAfter = aliceValidatorLocalBackend.getValidatorPartyId()
        aliceValidatorPartyAfter shouldBe validatorPartyBefore
      }
    }
  }

  private def startStandaloneCanton()(implicit env: CNNodeTestConsoleEnvironment) = {
    val cantonArgs = Seq(
      "-c",
      (svParticipantPath / "canton.conf").toString,
      "-c",
      (svDomainPath / "canton.conf").toString,
      "-c",
      (validatorParticipantPath / "canton.conf").toString,
      // avoid creating new identities
      "-C",
      "canton.participants-x.sv_participant.init.auto-init=false",
      "-C",
      "canton.participants-x.validator_participant.init.auto-init=false",
      // adjust user ids to account for the suffixing done by ensureNovelDamlNames
      "-C",
      "canton.participants-x.sv_participant.ledger-api.user-management-service." +
        s"additional-admin-user-id=${sv1LocalBackend.config.ledgerApiUser}",
      "-C",
      "canton.participants-x.validator_participant.ledger-api.user-management-service." +
        s"additional-admin-user-id=${aliceValidatorLocalBackend.config.ledgerApiUser}",
    )
    startCanton(cantonArgs, "participant-export-import")
  }

  // TODO(tech-debt) Consider removing this method in favor of making `useSelfSignedTokensForLedgerApiAuth` take an `ignore` parameter
  private def useSelfSignedTokensForLongRunningLedgerApiAuth(
      secret: String,
      config: CNNodeConfig,
  ): CNNodeConfig = {
    val enableAuth =
      CNNodeConfigTransforms.selfSignedTokenAuthSourceTransform(config.parameters.clock, secret)
    val transforms = Seq(
      CNNodeConfigTransforms.updateAllSvAppConfigs((name, c) =>
        if (name.endsWith("Local")) {
          c
        } else {
          c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
        }
      ),
      CNNodeConfigTransforms.updateAllValidatorConfigs((name, c) => {
        if (name.endsWith("Local")) {
          c
        } else {
          c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
        }
      }),
      CNNodeConfigTransforms.updateAllScanAppConfigs((name, c) => {
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
