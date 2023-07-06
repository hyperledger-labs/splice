package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc
import com.daml.network.config.CNNodeConfigTransforms.{
  updateAllAutomationConfigs,
  updateAllSvAppConfigs_,
}
import com.daml.network.config.{CNNodeConfigTransforms, GcpBucketConfig}
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
import java.time.Instant

// Separate from the export as we need a different config for SV1
abstract class AcsStoreDumpImportIntegrationTest[T <: SvBootstrapDumpConfig]
    extends CNNodeIntegrationTest
    with WalletTestUtil {

  def bootstrapDumpConfig(name: String): T

  // We use fixed dumps to test data continuity.
  // We expect to add new dumps and a corresponding test from test-net releases that fail staging
  // and required adaption of the dump import code.
  //
  // NOTE: use the following steps to produce such a dump
  //  1. Run the DirectoryAcsStoreDumpTriggerExportTimeBasedIntegrationTest to produce a dump file
  //  2. Open the produced json file in `apps/app/src/test/resources/dumps/test-outputs/<your-build-prefix>`
  //  3. Reformat it using 'jq' or IntelliJ and replace all package-ids with 'deadbeef'

  final protected val bootstrappingDumpFilename =
    AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpDir.resolve(
      "acs-dump_changed-package-ids.json"
    )

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .addConfigTransforms((_, config) =>
        updateAllAutomationConfigs(config =>
          config.copy(
            // Need to disable these triggers so we can comfortably use checkWallet
            enableAutomaticRewardsCollectionAndCoinMerging = false,
            enableSvRewards = false,
          )
        )(config)
      )
      // Set the static dump path
      .addConfigTransforms((_, config) =>
        updateAllSvAppConfigs_(c =>
          c.copy(
            onboarding = c.onboarding match {
              case Some(foundCollective: SvOnboardingConfig.FoundCollective) =>
                Some(
                  foundCollective.copy(
                    bootstrappingDump = Some(bootstrapDumpConfig(config.name.value))
                  )
                )
              case other => other
            }
          )
        )(config)
      )

  "sv1" should {
    "load the initial ACS dump" in { implicit env =>
      clue("Check that the open-mining rounds advanced by one tick have been restored") {
        // in an eventually because we rely on round change automation triggering once
        // (this seems to happen already before the actual test starts, but why not play it safe)
        eventually() {
          val openMiningRounds = sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(cc.round.OpenMiningRound.COMPANION)(
              svcParty
            )
          // we expect to have imported 1,2,3, and that a round change happens shortly after importing
          openMiningRounds.map(_.data.round.number).sorted shouldBe Seq(2L, 3L, 4L)
          // we expect that our testing dump is from quite some time ago
          openMiningRounds.filter(
            _.data.targetClosesAt.isBefore(Instant.now())
          ) should have length 2
        }
      }

      val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val charlie = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val dora = aliceValidatorBackend.onboardUser("dora_xyz")

      eventually() {
        sv1ScanBackend.listImportCrates(alice) should have size (0)
        sv1ScanBackend.listImportCrates(bob) should have size (0)
        sv1ScanBackend.listImportCrates(charlie) should have size (0)
        sv1ScanBackend.listImportCrates(dora) should have size (0)
      }

      // Note that coin merging and SV rewards are disabled for this test!

      // Alice had a locked coin and a normal coin that was initially 100 but got larger due to tapping 5.0 for setting up the directory entry
      checkWallet(alice, aliceWalletClient, Seq((10.0, 10), (103.0, 104.0)))
      checkWallet(bob, bobWalletClient, Seq((20.0, 20.0)))
      // Charlie has a coin and an import crate
      checkWallet(charlie, charlieWalletClient, Seq((30.0, 30.0), (30.0, 30.0)))
      // SV1 got some rewards that were exported
      checkWallet(sv1Backend.getSvcInfo().svParty, sv1WalletClient, Seq((66590.0, 66591.0)))
    }
  }

}

final class FileAcsStoreDumpImportIntegrationTest
    extends AcsStoreDumpImportIntegrationTest[SvBootstrapDumpConfig.File] {

  override def bootstrapDumpConfig(name: String) =
    SvBootstrapDumpConfig.File(bootstrappingDumpFilename)
}

final class GcpAcsStoreDumpImportIntegrationTest
    extends AcsStoreDumpImportIntegrationTest[SvBootstrapDumpConfig.Gcp] {

  private def bucketPath(name: String) = Paths.get(s"acs/import-test/dummy_${name}.json")
  override def bootstrapDumpConfig(name: String) = {
    val gcpBucket = new GcpBucket(GcpBucketConfig.inferForTesting, loggerFactory)
    val fileContent = better.files.File(bootstrappingDumpFilename).contentAsString
    gcpBucket.dumpStringToBucket(fileContent, bucketPath(name))
    SvBootstrapDumpConfig.Gcp(GcpBucketConfig.inferForTesting, bucketPath(name))
  }

}
