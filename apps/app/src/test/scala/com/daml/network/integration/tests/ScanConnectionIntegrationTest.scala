package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{ConfigScheduleUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

class ScanConnectionIntegrationTest
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the svc to consider unclaimed
      .withoutAutomaticRewardsCollectionAndCoinMerging

  "coin rules cache should be invalidated when the coin rules change" in { implicit env =>
    val (_, _) = onboardAliceAndBob()
    // tap once so the CoinRules are cached...
    aliceWalletClient.tap(5)
    val currentConfigSchedule = sv1ScanBackend.getCoinRules().contract.payload.configSchedule
    clue("schedule a config change, so the coinrules change, invalidating the cache.") {
      val configSchedule =
        createConfigSchedule(
          currentConfigSchedule,
          (
            defaultTickDuration.asJava,
            mkUpdatedCoinConfig(
              currentConfigSchedule,
              tickDuration = defaultTickDuration,
              maxNumInputs = 101,
            ),
          ),
        )

      setFutureConfigSchedule(configSchedule)
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
            s"CoinRules cache is empty or outdated, retrieving CoinRules from CC scan"
          )
        )
      },
    )
  }
}
