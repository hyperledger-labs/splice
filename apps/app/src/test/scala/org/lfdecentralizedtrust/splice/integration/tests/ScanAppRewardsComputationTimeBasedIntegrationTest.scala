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
      // TODO(#4118): Correct the time advancement
      // Currently this test advances time to ensure there are enough closed mining
      // rounds (rather than open mining rounds) because the trigger has not been
      // switched to use the as yet unmerged ScanRewardsReferenceStore
      //
      // Advance enough ticks so that at least two rounds are fully closed and aggregated.
      // (advanceRoundsToNextRoundOpening advances by 1 tick)
      for (_ <- 1 to 6) {
        advanceRoundsToNextRoundOpening
        sv1ScanBackend.automation
          .trigger[ScanAggregationTrigger]
          .runOnce()
          .futureValue
      }

      // Run the reward computation trigger
      sv1ScanBackend.automation
        .trigger[RewardComputationTrigger]
        .runOnce()
        .futureValue

      val earliest = clue("Verify earliest available round is returned") {
        val e = sv1ScanBackend.getRewardAccountingEarliestAvailableRound()
        e should not be empty
        e.value
      }

      clue("Verify activity totals for the aggregated round") {
        val totals = sv1ScanBackend.getRewardAccountingActivityTotals(earliest)
        totals should not be empty
        totals.value.roundNumber shouldBe earliest
      }

      val (lastRound, _) = sv1ScanBackend.getRoundOfLatestData()
      clue("Verify 404 for non-existent round") {
        sv1ScanBackend.getRewardAccountingActivityTotals(lastRound + 1000L) shouldBe None
      }
    }
  }
}
