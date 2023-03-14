package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*

class SvcTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms(
        (_, config) => {
          // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the svc to consider unclaimed
          CNNodeConfigTransforms.updateAllAutomationConfigs(
            _.focus(_.enableAutomaticRewardsCollectionAndCoinMerging).replace(false)
          )(config)
        },
        (_, config) => {
          // TODO(M3-63) Currently, auto-expiration of unclaimed rewards is disabled by default, and enabled only where needed.
          // In the cluster it currently cannot be enabled due to lack of resiliency to unavailable validators
          CNNodeConfigTransforms.updateAllAutomationConfigs(
            _.focus(_.enableUnclaimedRewardExpiration).replace(true)
          )(config)
        },
      )

  "auto-merge unclaimed rewards" in { implicit env =>
    val threshold =
      10 // TODO(M3-46): base this on the actual threshold read from the svcRules config
    val numRewards = threshold + 1
    val rewardAmount = 0.1

    def getUnclaimedRewardContracts() =
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(UnclaimedReward.COMPANION)(svcParty)

    val existingUnclaimedRewards = getUnclaimedRewardContracts().length

    actAndCheck(
      s"Create as many unclaimed rewards as needed to have at least ${numRewards}", {
        val unclaimedRewards = ((existingUnclaimedRewards + 1) to numRewards).map(_ =>
          new UnclaimedReward(svcParty.toProtoPrimitive, BigDecimal(rewardAmount).bigDecimal)
        )
        if (!unclaimedRewards.isEmpty) {
          svc.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
            actAs = Seq(svcParty),
            optTimeout = None,
            commands = unclaimedRewards.flatMap(_.create.commands.asScala.toSeq),
          )
        }
      },
    )(
      "Wait for the unclaimed rewards to get merged automagically",
      _ => {
        advanceTimeByPollingInterval(svc)
        getUnclaimedRewardContracts().length should (be < threshold)
      },
    )
  }

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
