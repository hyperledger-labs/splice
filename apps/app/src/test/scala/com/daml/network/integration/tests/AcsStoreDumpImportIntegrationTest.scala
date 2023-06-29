package com.daml.network.integration.tests

import com.daml.network.config.{CNNodeConfigTransforms, GcpBucketConfig}
import com.daml.network.config.CNNodeConfigTransforms.updateAllSvAppConfigs_
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.config.{SvBootstrapDumpConfig, SvOnboardingConfig}
import com.daml.network.util.{GcpBucket, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.nio.file.Paths

// Separate from the export as we need a different config for SV1
abstract class AcsStoreDumpImportIntegrationTest[T <: SvBootstrapDumpConfig]
    extends CNNodeIntegrationTest
    with WalletTestUtil {

  def bootstrapDumpConfig: T

  // We use fixed dumps to test data continuity.
  // We expect to add new dumps and a corresponding test from test-net releases that fail staging
  // and required adaption of the dump import code.

  // TODO(#6073): move to test/resources and remove the `./dumps` directory and its .gitignore once we dump to a Google Cloud Storage bucket
  protected val bootstrappingDumpFilename =
    Paths.get("dumps/static_test_data/test-dump_changed-package-ids.json")

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
                    bootstrappingDump = Some(bootstrapDumpConfig)
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

final class FileAcsStoreDumpIntegrationTest
    extends AcsStoreDumpImportIntegrationTest[SvBootstrapDumpConfig.File] {
  override def bootstrapDumpConfig = SvBootstrapDumpConfig.File(bootstrappingDumpFilename)
}

final class GcpAcsStoreDumpIntegrationTest
    extends AcsStoreDumpImportIntegrationTest[SvBootstrapDumpConfig.Gcp] {
  private val bucketPath = "acs/import-test/dummy.json"
  override def bootstrapDumpConfig =
    SvBootstrapDumpConfig.Gcp(GcpBucketConfig.inferForTesting, bucketPath)

  override def beforeAll() = {
    super.beforeAll()
    val gcpBucket = new GcpBucket(GcpBucketConfig.inferForTesting, loggerFactory)
    val fileContent = better.files.File(bootstrappingDumpFilename).contentAsString
    gcpBucket.dumpStringToBucket(fileContent, bucketPath)
  }
}
