package com.daml.network.integration.tests

import better.files.File
import com.daml.network.codegen.java.cc
import com.daml.network.config.CNNodeConfigTransforms.{
  updateAllAutomationConfigs,
  updateAllSvAppFoundCollectiveConfigs_,
}
import com.daml.network.config.GcpBucketConfig
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTest
import com.daml.network.sv.config.SvBootstrapDumpConfig
import com.daml.network.util.{GcpBucket, WalletTestUtil}
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import java.nio.file.{Files, Paths}

// Separate from the export as we need a different config for SV1
abstract class AcsStoreDumpImportIntegrationTest[T <: SvBootstrapDumpConfig]
    extends ParticipantIdentitiesImportTestBase
    with WalletTestUtil {

  def bootstrapDumpConfig(name: String): T

  // We use fixed dumps to test data continuity.
  // We expect to add new dumps and a corresponding test from test-net releases that fail staging
  // and required adaption of the dump import code.
  //
  // NOTE: use the following steps to produce required dumps
  //  1. Run the CombinedDumpDirectoryExportTimeBasedIntegrationTest to produce 3 dump files:
  //     one of the acs, and one of the participants of sv1 and alice
  //  2. Copy the produced json files from `apps/app/src/test/resources/dumps/test-outputs/`
  //  3. Only for the ACS dump: Reformat it using 'jq' or IntelliJ and replace all package-ids with 'deadbeef'

  final protected val acsDumpFilename =
    AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpDir.resolve(
      "acs-dump_changed-package-ids.json"
    )

  override def sv1ParticipantDumpFilename =
    AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpDir.resolve(
      "sv1_participant_dump.json"
    )

  override def aliceParticipantDumpFilename =
    AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpDir.resolve(
      "alice_participant_dump.json"
    )

  override def environmentDefinition: CNNodeEnvironmentDefinition =
    super.environmentDefinition
      .addConfigTransforms(
        (_, config) =>
          updateAllAutomationConfigs(c =>
            c.copy(
              // Need to disable these triggers so we can comfortably use checkWallet
              enableAutomaticRewardsCollectionAndCoinMerging = false,
              enableSvRewards = false,
            )
          )(config),
        (_, config) =>
          updateAllSvAppFoundCollectiveConfigs_ { c =>
            c.copy(bootstrappingDump = Some(bootstrapDumpConfig(config.name.value)))
          }(config),
      )

  // Runs against a temporary Canton instance.
  override lazy val resetDecentralizedNamespace = false

  "sv1" should {
    "load the initial ACS dump" in { implicit env =>
      usingStandaloneCantonWithNewCn {
        clue("Start sv1Local, sv1ScanLocal, sv1ValidatorLocal, aliceValidatorLocal")(
          loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.INFO))(
            startAllSync(
              sv1LocalBackend,
              sv1ScanLocalBackend,
              sv1ValidatorLocalBackend,
              aliceValidatorLocalBackend,
            ),
            logEntries => {
              forAtLeast(1, logEntries)(logEntry => {
                logEntry.loggerName should endWith("SV=sv1Local")
                logEntry.message should startWith("Validator license for SV party already exists")
              })
              forAtLeast(1, logEntries)(logEntry => {
                logEntry.loggerName should endWith("validator=aliceValidatorLocal")
                logEntry.message should startWith("ValidatorLicense found => already onboarded")
              })
            },
          )
        )
        clue("Check that there are no duplicate validator licenses") {
          val licenses = sv1LocalBackend.participantClient.ledger_api_extensions.acs
            .filterJava(cc.validatorlicense.ValidatorLicense.COMPANION)(
              svcParty
            )
          val duplicates = licenses
            .groupBy(_.data.validator)
            .collect { case (validator, ls) if ls.length > 1 => validator }
            .toSeq
          duplicates shouldBe empty
        }

        clue("Check that the open-mining rounds advanced by one tick have been restored") {
          // in an eventually because we rely on round change automation triggering once
          // (this seems to happen already before the actual test starts, but why not play it safe)
          eventually() {
            val openMiningRounds = sv1LocalBackend.participantClient.ledger_api_extensions.acs
              .filterJava(cc.round.OpenMiningRound.COMPANION)(
                svcParty
              )
            // we expect to have bootstrapped from round 1
            openMiningRounds.map(_.data.round.number).sorted shouldBe Seq(1L, 2L, 3L)
          }
        }
        val alice = onboardWalletUser(aliceWalletClient, aliceValidatorLocalBackend)
        val charlie = onboardWalletUser(charlieWalletClient, aliceValidatorLocalBackend)
        val dora = aliceValidatorLocalBackend.onboardUser("dora_xyz")

        eventually() {
          sv1ScanLocalBackend.listImportCrates(alice) should have size (0)
          sv1ScanLocalBackend.listImportCrates(charlie) should have size (0)
          sv1ScanLocalBackend.listImportCrates(dora) should have size (0)
        }

        // Note that coin merging and SV rewards are disabled for this test!

        // Alice had a locked coin and a normal coin that was initially 100 but got larger due to tapping 5.0 for setting up the cns entry
        checkWallet(alice, aliceWalletClient, Seq((10.0, 10), (103.0, 104.0)))
        // Charlie has a coin and an import crate
        checkWallet(charlie, charlieWalletClient, Seq((30.0, 30.0), (30.0, 30.0)))
        // SV1 got some rewards that were exported
        checkWallet(sv1LocalBackend.getSvcInfo().svParty, sv1WalletClient, Seq((66590.0, 66591.0)))
      }
    }
  }

  // changes user names to match our current test ID
  def fixUserSuffixes(acsDump: String, currentSuffix: String): String = {

    val dumpSuffix = clue("extract suffix used in dump") {
      val suffixRegexMatch =
        """"svc"\s*:\s*"svc.*-(.*?)-(.*?)::.*"""".r.findFirstMatchIn(acsDump).value
      // sanity check; this is what our current dumps look like
      suffixRegexMatch.group(1) shouldBe suffixRegexMatch.group(2)
      suffixRegexMatch.group(1)
    }
    acsDump
      // replace the test ID suffix
      .replace(dumpSuffix, currentSuffix)
      // remove any additional test case suffix,
      // which could have been added if the dump was created in a
      // CNNodeIntegrationTestWithSharedEnvironment
      .replaceAll("__tc\\d+", "")
  }
}

final class FileAcsStoreDumpImportIntegrationTest
    extends AcsStoreDumpImportIntegrationTest[SvBootstrapDumpConfig.File] {

  override def bootstrapDumpConfig(name: String) = {
    val fileContent = File(acsDumpFilename).contentAsString
    val fixedFileContent = fixUserSuffixes(fileContent, name)
    val fixedFile: File = Files.createTempFile("acs-dump_fixed_test_suffixes", ".json")
    fixedFile.overwrite(fixedFileContent)
    SvBootstrapDumpConfig.File(fixedFile.path)
  }
}

final class GcpAcsStoreDumpImportIntegrationTest
    extends AcsStoreDumpImportIntegrationTest[SvBootstrapDumpConfig.Gcp] {

  import scala.concurrent.blocking

  var bucketInitialized: Boolean = false

  // There are multiple founding SV apps in the test so the config
  // transform can get run multiple times.  Rather than trying to be
  // clever and selecting the right one by name, we just ensure that
  // the upload is cached.
  def initializeBucket(name: String) = blocking {
    synchronized {
      if (!bucketInitialized) {
        val gcpBucket = new GcpBucket(GcpBucketConfig.inferForTesting, loggerFactory)
        val fileContent = File(acsDumpFilename).contentAsString
        val fixedFileContent = fixUserSuffixes(fileContent, name)
        gcpBucket.dumpStringToBucket(fixedFileContent, bucketPath(name))
        bucketInitialized = true
      }
    }
  }

  private def bucketPath(name: String) = Paths.get(s"acs/import-test/dummy_${name}.json")
  override def bootstrapDumpConfig(name: String) = {
    initializeBucket(name)
    SvBootstrapDumpConfig.Gcp(GcpBucketConfig.inferForTesting, bucketPath(name))
  }
}

final class FileEmptyAcsStoreDumpImportIntegrationTest extends CNNodeIntegrationTest {
  final protected val emptyAcsDumpFilename =
    AcsStoreDumpTriggerExportTimeBasedIntegrationTest.testDumpDir.resolve(
      "acs-dump_empty.json"
    )

  def bootstrapDumpConfig() = {
    val fileContent = File(emptyAcsDumpFilename).contentAsString
    val fixedFileContent = fileContent
    val fixedFile: File = Files.createTempFile("empty-acs-dump_fixed_test_suffixes", ".json")
    fixedFile.overwrite(fixedFileContent)
    SvBootstrapDumpConfig.File(fixedFile.path)
  }

  override def environmentDefinition: CNNodeEnvironmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms(
        (_, config) =>
          updateAllAutomationConfigs(c =>
            c.copy(
              // Need to disable these triggers so we can comfortably use checkWallet
              enableAutomaticRewardsCollectionAndCoinMerging = false,
              enableSvRewards = false,
            )
          )(config),
        (_, config) =>
          updateAllSvAppFoundCollectiveConfigs_ { c =>
            c.copy(bootstrappingDump = Some(bootstrapDumpConfig()))
          }(config),
      )
      .withManualStart

  "sv1" should {
    "not crash when loading a acs dump with no contracts" in { implicit env =>
      clue("Start sv1 with empty acs dump configured")(
        loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.DEBUG))(
          startAllSync(sv1ScanBackend, sv1ValidatorBackend, sv1Backend),
          logEntries => {
            forAtLeast(1, logEntries)(logEntry => {
              logEntry.loggerName should endWith("SV=sv1")
              logEntry.message should startWith(
                "Skipping importing an ACS snapshot, as no ImportCommands are extracted from the acs dump."
              )
            })
          },
        )
      )
    }
  }
}
