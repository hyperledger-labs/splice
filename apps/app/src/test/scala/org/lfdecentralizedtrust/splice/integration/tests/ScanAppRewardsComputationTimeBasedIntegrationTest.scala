package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.scan.automation.{
  RewardComputationTrigger,
  ScanAggregationTrigger,
}
import org.lfdecentralizedtrust.splice.scan.config.SequencerTrafficIngestionConfig
import org.lfdecentralizedtrust.splice.util.*

class ScanAppRewardsComputationTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with HasExecutionContext {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Scan)(
          _.withPausedTrigger[ScanAggregationTrigger]
            .withPausedTrigger[RewardComputationTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs((_, scanConfig) =>
          scanConfig.copy(
            sequencerTrafficIngestion = SequencerTrafficIngestionConfig(enabled = true)
          )
        )(config)
      )
      .withAdditionalSetup { implicit _env =>
        advanceTimeForRewardAutomationToRunForCurrentRound
      }

  "reward accounting endpoints" should {
    "return 404 when no data is available" in { implicit _env =>
      clue("earliest-available returns None") {
        sv1ScanBackend.getRewardAccountingEarliestAvailableRound() shouldBe None
      }
      clue("activity-totals returns None for round 0") {
        sv1ScanBackend.getRewardAccountingActivityTotals(0L) shouldBe None
      }
    }

    "return activity totals after aggregation trigger runs" in { implicit _env =>
      // TODO(#4384): Update the time management here
      //
      // Advance enough ticks so that at least three rounds are fully closed and aggregated.
      // (advanceRoundsToNextRoundOpening advances by 1 tick)
      for (_ <- 1 to 7) {
        advanceRoundsToNextRoundOpening
        sv1ScanBackend.automation
          .trigger[ScanAggregationTrigger]
          .runOnce()
          .futureValue
      }

      clue("Run the reward computation trigger") {
        sv1ScanBackend.automation
          .trigger[RewardComputationTrigger]
          .runOnce()
          .futureValue
      }

      val earliest = clue("Verify earliest available round is returned") {
        val e = sv1ScanBackend.getRewardAccountingEarliestAvailableRound()
        e.value shouldBe 1
        e.value
      }

      clue("Verify activity totals for the aggregated round") {
        val totals = sv1ScanBackend.getRewardAccountingActivityTotals(earliest)
        totals.value.roundNumber shouldBe earliest
        totals.value.numActivityRecordsInRound should be > 0L
      }

      clue("Verify 404 for non-existent round") {
        sv1ScanBackend.getRewardAccountingActivityTotals(earliest + 1) shouldBe None
      }
    }
  }
}
