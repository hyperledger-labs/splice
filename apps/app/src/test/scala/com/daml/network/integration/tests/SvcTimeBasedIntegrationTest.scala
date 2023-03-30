package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

class SvcTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the svc to consider unclaimed
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .addConfigTransforms((_, config) => {
        // TODO(M3-63) Currently, auto-expiration of unclaimed rewards is disabled by default, and enabled only where needed.
        // In the cluster it currently cannot be enabled due to lack of resiliency to unavailable validators
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableUnclaimedRewardExpiration).replace(true)
        )(config)
      })

  "coin rules cache should be invalidated when the coin rules change" in { implicit env =>
    val (_, _) = onboardAliceAndBob()
    // tap once so the CoinRules are cached...
    aliceWallet.tap(5)
    clue("schedule a config change, so the coinrules change, invalidating the cache.") {
      val configSchedule =
        createConfigSchedule((defaultTickDuration.duration, mkCoinConfig(maxNumInputs = 101)))
      svcClient.setConfigSchedule(configSchedule)
    }

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      // tapping again..
      aliceWallet.tap(5),
      entries => {
        forAtLeast(
          1,
          entries,
        )( // .. will initially fail and lead to a cache invalidation..
          _.message should include regex (
            s"Invalidating the CoinRules cache"
          )
        )
        forAtLeast(
          1,
          entries,
        )(
          _.message should include regex ( // and cache refreshment
            s"CoinRules cache is empty or outdated, retrieving CoinRules from CC scan"
          )
        )
      },
    )
  }
}
