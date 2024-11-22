package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File
import org.lfdecentralizedtrust.splice.config.{
  ConfigTransforms,
  ParticipantBootstrapDumpConfig,
  SpliceConfig,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ensureNovelDamlNames,
  selfSignedTokenAuthSourceTransform,
  updateAllScanAppConfigs,
  updateAllSvAppConfigs,
  updateAllValidatorConfigs,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.ProcessTestUtil
import monocle.macros.syntax.lens.*
import java.nio.file.Path

abstract class ParticipantIdentitiesImportTestBase extends IntegrationTest with ProcessTestUtil {

  val svNodePath: File = testResourcesPath / "local-sv-node"
  val validatorNodePath: File = testResourcesPath / "local-validator-node"

  val svParticipantPath: File = svNodePath / "canton-participant"
  val svDomainPath: File = svNodePath / "canton-domain"
  val svAppPath: File = svNodePath / "sv-app"
  val scanAppPath: File = svNodePath / "scan-app"
  val svValidatorAppPath: File = svNodePath / "validator-app"

  val validatorAppPath: File = validatorNodePath / "validator-app"

  def aliceParticipantDumpFilename: Path

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
      .addConfigTransforms((_, config) =>
        ConfigTransforms.withPausedSvDomainComponentsOffboardingTriggers()(config)
      )
      .addConfigTransforms(
        (_, config) => ensureNovelDamlNames()(config),
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
                      aliceParticipantDumpFilename,
                      Some(s"aliceValidatorLocal-$randomParticipantSuffix"),
                    )
                )
              )
            } else {
              c
            }
          }(config),
      )
      .withAllocatedUsers()
      // A short polling interval is required by UpdateHistorySanityCheckPlugin
      .addConfigTransform((_, config) => ConfigTransforms.reducePollingInterval(config))
      .withManualStart

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
