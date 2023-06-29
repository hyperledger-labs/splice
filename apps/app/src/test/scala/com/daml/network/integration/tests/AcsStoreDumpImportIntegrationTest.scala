package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.config.CNNodeConfigTransforms.updateAllSvAppConfigs_
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.config.SvOnboardingConfig
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

// Separate from the export as we need a different config for SV1
class AcsStoreDumpImportIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  // We use fixed dumps to test data continuity.
  // We expect to add new dumps and a corresponding test from test-net releases that fail staging
  // and required adaption of the dump import code.

  // TODO(#6073): move to test/resources and remove the `./dumps` directory and its .gitignore once we dump to a Google Cloud Storage bucket
  private val bootstrappingDumpFilename = {
    "dumps/static_test_data/test-dump_changed-package-ids.json"
  }

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      // Set the static dump path
      .addConfigTransforms((_, config) =>
        updateAllSvAppConfigs_(c =>
          c.copy(
            onboarding = c.onboarding match {
              case Some(foundCollective: SvOnboardingConfig.FoundCollective) =>
                Some(
                  foundCollective.copy(
                    bootstrappingDump = Some(bootstrappingDumpFilename)
                  )
                )
              case other => other
            }
          )
        )(config)
      )

  "sv1" should {
    "load the initial ACS dump" in { implicit env =>
      val alice = onboardWalletUser(aliceWallet, aliceValidatorBackend)
      val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val charlie = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val dora = aliceValidatorBackend.onboardUser("dora_xyz")

      eventually() {
        sv1ScanBackend.listImportCrates(alice) should have size (0)
        sv1ScanBackend.listImportCrates(bob) should have size (0)
        sv1ScanBackend.listImportCrates(charlie) should have size (0)
        sv1ScanBackend.listImportCrates(dora) should have size (0)
      }

      // Note: we import two coins, but they get merged
      checkWallet(alice, aliceWallet, Seq((109.0, 110.0)))
      checkWallet(bob, bobWalletClient, Seq((20.0, 20.0)))
      checkWallet(charlie, charlieWalletClient, Seq((30.0, 30.0)))
    }
  }

}
