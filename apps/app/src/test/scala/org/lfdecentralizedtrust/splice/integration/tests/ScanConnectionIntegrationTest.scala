package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{AmuletConfigUtil, WalletTestUtil}
import org.slf4j.event.Level

import scala.concurrent.duration.DurationInt

class ScanConnectionIntegrationTest
    extends IntegrationTest
    with AmuletConfigUtil
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the dso to consider unclaimed
      .withoutAutomaticRewardsCollectionAndAmuletMerging

  "amulet rules cache should be invalidated when the amulet rules change" in { implicit env =>
    val (_, _) = onboardAliceAndBob()
    // tap once so the AmuletRules are cached...
    aliceWalletClient.tap(5)
    val baseConfig = sv1ScanBackend.getAmuletRules().contract.payload.configSchedule.initialValue
    clue("change the amulet config to invalidate the cache.") {
      val newConfig =
        mkUpdatedAmuletConfig(
          sv1ScanBackend.getAmuletRules().contract,
          tickDuration = defaultTickDuration,
          maxNumInputs = 101,
        )

      setAmuletConfig(Seq((Some(durationUntilOffboardingEffectivity), newConfig, baseConfig)))
    }
    eventually(40.seconds) {
      sv1ScanBackend
        .getAmuletRules()
        .contract
        .payload
        .configSchedule
        .initialValue
        .transferConfig
        .maxNumInputs shouldBe 101
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      // tapping again..
      aliceWalletClient.tap(5),
      entries => {
        forAtLeast(
          1,
          entries,
        )(
          // will initially fail and lead to a cache invalidation.
          // then the retry logic in tap will trigger cache refreshment
          _.message should include regex (
            s"AmuletRules cache is empty or outdated, retrieving AmuletRules from CC scan"
          )
        )
      },
    )
  }
}
