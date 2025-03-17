package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{ConfigScheduleUtil, WalletTestUtil}
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

class ScanConnectionIntegrationTest
    extends IntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the dso to consider unclaimed
      .withoutAutomaticRewardsCollectionAndAmuletMerging

  "amulet rules cache should be invalidated when the amulet rules change" in { implicit env =>
    val (_, _) = onboardAliceAndBob()
    // tap once so the AmuletRules are cached...
    aliceWalletClient.tap(5)
    val currentConfigSchedule = sv1ScanBackend.getAmuletRules().contract.payload.configSchedule
    clue("schedule a config change, so the amuletrules change, invalidating the cache.") {
      val configSchedule =
        createConfigSchedule(
          currentConfigSchedule,
          (
            defaultTickDuration.asJava,
            mkUpdatedAmuletConfig(
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
            s"AmuletRules cache is empty or outdated, retrieving AmuletRules from CC scan"
          )
        )
      },
    )
  }
}
