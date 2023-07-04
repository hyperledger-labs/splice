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

  val svParticipantPath: File = svNodePath / "canton-participant"
  val svDomainPath: File = svNodePath / "canton-domain"
  val svAppsPath: File = svNodePath / "sv-apps"

  lazy val dumpFile: File = Files.createTempFile("participant-dump", ".json")

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] = {
    CNNodeEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        // Config that runs against long-running Canton; sv1 defined here
        testResourcesPath / "simple-topology.conf",
        // Config that runs against Canton started from this test; sv1-local defined here
        svAppsPath / "app.conf",
      )
      .clearConfigTransforms()
      .addConfigTransforms(
        (_, config) => CNNodeConfigTransforms.ensureNovelDamlNames()(config),
        (_, config) => useSelfSignedTokensForLongRunningLedgerApiAuth("test", config),
        (_, config) => useSelfSignedTokensForLongRunningLedgerApiAuth("test", config),
        (_, config) => useSelfSignedTokensForLongRunningLedgerApiAuth("test", config),
        (_, config) =>
          CNNodeConfigTransforms.updateAllSvAppConfigs { case (name, c) =>
            if (name == "sv1Local") {
              c.copy(participantBootstrappingDump =
                Some(ParticipantBootstrapDumpConfig.File(dumpFile.path))
              )
            } else {
              c
            }
          }(config),
      )
      .withAllocatedUsers()
      .withManualStart
  }

  "We can export a Canton participant identity and import it in a new participant" in {
    implicit env =>
      startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)
      val svcInfoBefore = sv1Backend.getSvcInfo()

      val participantDump = clue("Getting participant identities dump") {
        sv1ValidatorBackend.dumpParticipantIdentities()
      }
      clue("Checking exported users list") {
        val sv1Party = svcInfoBefore.svParty
        participantDump.users should contain(
          ParticipantIdentitiesDump.ParticipantUser(sv1Backend.config.ledgerApiUser, Some(sv1Party))
        )
        participantDump.users should contain(
          ParticipantIdentitiesDump.ParticipantUser(
            sv1ValidatorBackend.config.ledgerApiUser,
            Some(sv1Party),
          )
        )
        participantDump.users should contain(
          ParticipantIdentitiesDump.ParticipantUser(
            sv1ValidatorBackend.config.validatorWalletUser.value,
            Some(sv1Party),
          )
        )
      }

      dumpFile.overwrite(participantDump.toJson.spaces2)

      sv1ValidatorBackend.stop()
      sv1ScanBackend.stop()
      sv1Backend.stop()

      Using.resource(startStandaloneCanton()(env)) { _ =>
        sv1LocalBackend.startSync()

        val svcInfoAfter = sv1LocalBackend.getSvcInfo()

        svcInfoAfter.svParty shouldBe svcInfoBefore.svParty
        svcInfoAfter.svcParty shouldBe svcInfoBefore.svcParty

      // TODO(#6073) also spin up a new validator app and compare the exported participant identity
      }
  }

  private def startStandaloneCanton()(implicit env: CNNodeTestConsoleEnvironment) = {
    val cantonArgs = Seq(
      "-c",
      (svParticipantPath / "canton.conf").toString,
      "-c",
      (svDomainPath / "canton.conf").toString,
      "-C",
      "canton.participants-x.sv_participant.init.auto-init=false", // avoid creating new identity
      "-C",
      // adjust sv user id to account for the suffixing done by ensureNovelDamlNames
      "canton.participants-x.sv_participant.ledger-api.user-management-service." +
        s"additional-admin-user-id=${sv1LocalBackend.config.ledgerApiUser}",
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
      CNNodeConfigTransforms.updateAllValidatorConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      CNNodeConfigTransforms.updateAllScanAppConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.svUser, _))
      }),
    )
    transforms.foldLeft(config)((c, tf) => tf(c))
  }
}
