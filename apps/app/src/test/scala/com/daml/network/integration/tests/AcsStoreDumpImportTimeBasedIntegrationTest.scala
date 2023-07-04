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

// Separate from the export as we need a different config for SV1
abstract class AcsStoreDumpImportTimeBasedIntegrationTest[T <: SvBootstrapDumpConfig]
    extends CNNodeIntegrationTest
    with WalletTestUtil {

  def bootstrapDumpConfig(name: String): T

  // We use fixed dumps to test data continuity.
  // We expect to add new dumps and a corresponding test from test-net releases that fail staging
  // and required adaption of the dump import code.
  //
  // NOTE: use the following steps to produce such a dump
  //  1. Run the DirectoryAcsStoreDumpTriggerExportTimeBasedIntegrationTest to produce a dump file
  //  2. Open the produced json file in `dumps/testing/<your-build-prefix>`
  //  3. Reformat it using 'jq' or IntelliJ and replace
  //     a. all package-ids with 'deadbeef'
  //     b. the year timestamps of all timestmpas ('1970') with ('1800') so that the imported OpenMiningRounds
  //        are definitely in the past, even when using simtime.

  // TODO(#6073): move to test/resources and remove the `./dumps` directory and its .gitignore once we dump to a Google Cloud Storage bucket
  protected val bootstrappingDumpFilename =
    Paths.get("dumps/static_test_data/test-dump_changed-package-ids.json")

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      // Using SimTime to get control over rounds not advancing.
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .addConfigTransforms((_, config) =>
        updateAllAutomationConfigs(config =>
          config.copy(
            // Need to disable these triggers so we can use checkWallet
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
      clue("Check that the open-mining rounds advanced by one ticket have been restored") {
        val openMiningRounds = sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(cc.round.OpenMiningRound.COMPANION)(
            svcParty
          )
        inside(openMiningRounds.map(_.data.round.number).sorted) { roundNumbers =>
          // We can't check for exact equality as the simtime clock is shared with other simtime tests in
          // CI, and thus might be farther than unix epoch zero, which in turn triggers the AdvanceOpenMiningRounds
          // trigger. The test is still reasonably useful, as the initial round would be round 0 in a freshly
          // initialized consortium.
          // TODO(#6408): use control over what trigger are running to improve the precision of this test
          val minRound: Long = roundNumbers.min
          minRound should (be >= 1L)
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

      // Alice had a normal and a locked coin, which are not getting merged as we don't advance time
      checkWallet(alice, aliceWalletClient, Seq((10.0, 10), (99.0, 100.0)))
      checkWallet(bob, bobWalletClient, Seq((20.0, 20.0)))
      // Charlie has a coin and an import crate
      checkWallet(charlie, charlieWalletClient, Seq((30.0, 30.0), (30.0, 30.0)))
      // SV1 got some rewards that were exported
      checkWallet(sv1Backend.getSvcInfo().svParty, sv1WalletClient, Seq((66590.0, 66591.0)))
    }
  }

}

final class FileAcsStoreDumpImportTimeBasedIntegrationTest
    extends AcsStoreDumpImportTimeBasedIntegrationTest[SvBootstrapDumpConfig.File] {

  override def bootstrapDumpConfig(name: String) =
    SvBootstrapDumpConfig.File(bootstrappingDumpFilename)
}

final class GcpAcsStoreDumpImportTimeBasedIntegrationTest
    extends AcsStoreDumpImportTimeBasedIntegrationTest[SvBootstrapDumpConfig.Gcp] {

  private def bucketPath(name: String) = Paths.get(s"acs/import-test/dummy_${name}.json")
  override def bootstrapDumpConfig(name: String) = {
    val gcpBucket = new GcpBucket(GcpBucketConfig.inferForTesting, loggerFactory)
    val fileContent = better.files.File(bootstrappingDumpFilename).contentAsString
    gcpBucket.dumpStringToBucket(fileContent, bucketPath(name))
    SvBootstrapDumpConfig.Gcp(GcpBucketConfig.inferForTesting, bucketPath(name))
  }

}
