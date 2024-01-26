package com.daml.network.integration.tests

import better.files.File
import com.daml.network.config.{
  CNNodeConfig,
  CNNodeConfigTransforms,
  ParticipantBootstrapDumpConfig,
}
import com.daml.network.config.CNNodeConfigTransforms.{
  ensureNovelDamlNames,
  selfSignedTokenAuthSourceTransform,
  updateAllScanAppConfigs,
  updateAllSvAppConfigs,
  updateAllValidatorConfigs,
}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.ProcessTestUtil
import monocle.macros.syntax.lens.*

import java.nio.file.Path
import scala.util.Using

abstract class ParticipantIdentitiesImportTestBase
    extends CNNodeIntegrationTest
    with ProcessTestUtil {

  val svNodePath: File = testResourcesPath / "local-sv-node"
  val validatorNodePath: File = testResourcesPath / "local-validator-node"

  val svParticipantPath: File = svNodePath / "canton-participant"
  val svDomainPath: File = svNodePath / "canton-domain"
  val svAppPath: File = svNodePath / "sv-app"
  val scanAppPath: File = svNodePath / "scan-app"
  val svValidatorAppPath: File = svNodePath / "validator-app"

  val validatorParticipantPath: File = validatorNodePath / "canton-participant"
  val validatorAppPath: File = validatorNodePath / "validator-app"

  def sv1ParticipantDumpFilename: Path
  def aliceParticipantDumpFilename: Path

  override def environmentDefinition: CNNodeEnvironmentDefinition =
    CNNodeEnvironmentDefinition
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
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.withPauseSvDomainComponentsOffboardingTriggers()(config)
      )
      .addConfigTransforms(
        (_, config) => ensureNovelDamlNames()(config),
        (_, config) => useSelfSignedTokensForLongRunningLedgerApiAuth("test", config),
        (_, config) =>
          updateAllSvAppConfigs { case (name, c) =>
            if (name == "sv1Local") {
              c.copy(participantBootstrappingDump =
                Some(ParticipantBootstrapDumpConfig.File(sv1ParticipantDumpFilename))
              )
            } else {
              c
            }
          }(config),
        (_, config) =>
          updateAllValidatorConfigs { case (name, c) =>
            if (name == "aliceValidatorLocal") {
              c.copy(participantBootstrappingDump =
                Some(ParticipantBootstrapDumpConfig.File(aliceParticipantDumpFilename))
              )
            } else {
              c
            }
          }(config),
      )
      .withAllocatedUsers()
      .withManualStart

  def usingStandaloneCantonWithNewCn[T](action: => T)(implicit
      env: CNNodeTestConsoleEnvironment
  ) =
    Using.resource(startStandaloneCanton()) { _ => action }

  private def startStandaloneCanton()(implicit env: CNNodeTestConsoleEnvironment) = {
    startCanton(
      Seq(
        svParticipantPath / "canton.conf",
        svDomainPath / "canton.conf",
        validatorParticipantPath / "canton.conf",
      ),
      Seq(
        // avoid creating new identities
        "canton.participants.sv_participant.init.auto-init=false",
        "canton.participants.validator_participant.init.auto-init=false",
        // adjust user ids to account for the suffixing done by ensureNovelDamlNames
        "canton.participants.sv_participant.ledger-api.user-management-service." +
          s"additional-admin-user-id=${sv1LocalBackend.config.ledgerApiUser}",
        "canton.participants.validator_participant.ledger-api.user-management-service." +
          s"additional-admin-user-id=${aliceValidatorLocalBackend.config.ledgerApiUser}",
      ),
      "participant-export-import",
    )
  }

  // TODO(tech-debt) Consider removing this method in favor of making `useSelfSignedTokensForLedgerApiAuth` take an `ignore` parameter
  private def useSelfSignedTokensForLongRunningLedgerApiAuth(
      secret: String,
      config: CNNodeConfig,
  ): CNNodeConfig = {
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
