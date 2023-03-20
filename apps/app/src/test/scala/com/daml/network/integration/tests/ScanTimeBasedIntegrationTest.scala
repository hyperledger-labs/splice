package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil, CoinUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import scala.jdk.CollectionConverters.*
import com.daml.network.config.CNNodeConfigTransforms
import monocle.macros.syntax.lens.*

class ScanTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableAutomaticRewardsCollectionAndCoinMerging).replace(false)
        )(config)
      )

  "report correct reference data" in { implicit env =>
    scan.getLatestOpenMiningRound(getLedgerTime).payload.round.number shouldBe 1

    advanceRoundsByOneTick
    scan.getLatestOpenMiningRound(getLedgerTime).payload.round.number shouldBe 2
  }

  "return correct coin configs" in { implicit env =>
    // TODO(#2930) test also with changing coin prices.
    // TODO(#2930) Currently we are not guaranteed that the first three rounds are correctly
    // captured in the tx log, so for now we first advance a round, and query only on that
    // round and beyond. Once that is fixed, we should make sure that querying for round 0 is reliable as well.

    advanceRoundsByOneTick

    clue("Get config for round 3") {
      val cfg = eventuallySucceeds() {
        scan.getCoinConfigForRound(3)
      }
      cfg.coinCreateFee.bigDecimal.setScale(10) should be(
        CoinUtil.defaultCreateFee.fee.setScale(10)
      )
      cfg.holdingFee.bigDecimal.setScale(10) should be(
        CoinUtil.defaultHoldingFee.rate.setScale(10)
      )
      cfg.lockHolderFee.bigDecimal.setScale(10) should be(
        CoinUtil.defaultLockHolderFee.fee.setScale(10)
      )
      cfg.transferFee.initial.bigDecimal.setScale(10) should be(
        CoinUtil.defaultTransferFee.initialRate.setScale(10)
      )
      cfg.transferFee.steps shouldBe (
        CoinUtil.defaultTransferFee.steps.asScala.toSeq.map(step =>
          HttpScanAppClient.RateStep(step._1, step._2)
        )
      )
    }

    clue("Try to get config for round 4 which does not yet exist") {
      assertThrowsAndLogsCommandFailures(
        scan.getCoinConfigForRound(4),
        _.errorMessage should include("Round 4 not found"),
      )
    }

    val newHoldingFee = 0.1
    clue("schedule a config change, and advance time for it to take effect") {
      val configSchedule =
        createConfigSchedule(
          (defaultTickDuration.duration, mkCoinConfig(holdingFee = newHoldingFee))
        )
      svcClient.setConfigSchedule(configSchedule)
      advanceRoundsByOneTick
    }
    clue("Round 4 should now be open, and have the new configuration") {
      eventuallySucceeds() {
        scan.getCoinConfigForRound(4).holdingFee should be(newHoldingFee)
      }
    }
  }

  "support app and validator leaderboards" in { implicit env =>
    val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
    waitForWalletUser(aliceValidatorWallet)
    waitForWalletUser(bobValidatorWallet)

    clue("Tap to get some coins") {
      aliceWallet.tap(500.0)
      bobWallet.tap(500.0)
      aliceValidatorWallet.tap(100.0)
    }

    clue("Transfer some CC, to generate reward coupons")({
      p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bobUserParty, 40.0)
      p2pTransferAndTriggerAutomation(bobWallet, aliceWallet, aliceUserParty, 100.0)
    })
    clue("Advance 3 ticks for the coupons to be collectable")({
      advanceRoundsByOneTick
      advanceRoundsByOneTick
      advanceRoundsByOneTick
    })
    clue("Alice and Bob use their app rewards when transfering CC")({
      p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bobUserParty, 10.0)
      p2pTransferAndTriggerAutomation(bobWallet, aliceWallet, aliceUserParty, 10.0)
    })
    clue("Alice's and Bob's validators use their validator reward when transfering CC")({
      // TODO(#3469): for now we just inspected the log manually to see that the correct entries are
      // appended to the scan tx log. Once there's a proper API for the leaderboard, we'll add a check here
      p2pTransferAndTriggerAutomation(aliceValidatorWallet, bobWallet, bobUserParty, 10.0)
      p2pTransferAndTriggerAutomation(bobValidatorWallet, aliceWallet, aliceUserParty, 10.0)
    })
    clue("No aggregate round data should be available yet")({
      assertThrowsAndLogsCommandFailures(
        scan.getRoundOfLatestData(),
        _.errorMessage should include("No data has been made available yet"),
      )
    })
    actAndCheck("Advance one more tick for round 0 to close", advanceRoundsByOneTick)(
      "Latest round with data should be updated",
      _ =>
        eventuallySucceeds() {
          val round = scan.getRoundOfLatestData()
          round should be(0)
        },
    )
    clue("Data for a later round does not yet exist")(
      assertThrowsAndLogsCommandFailures(
        scan.getTopProvidersByAppRewards(4, 10),
        _.errorMessage should include("Data for round 4 not yet computed"),
      )
    )
    actAndCheck(
      "Advance four more rounds, for round 4 to close (where rewards were claimed)",
      Range(0, 4).foreach(_ => advanceRoundsByOneTick),
    )(
      "Test leaderboards",
      _ => {
        eventually() {
          scan.getRoundOfLatestData() should be(4)
        }
        val appLeaderboard = scan.getTopProvidersByAppRewards(4, 10)
        // TODO(#3469): slightly more extensive testing, and consider de-hard-coding the expected values below, and computing them from the defaults instead.
        appLeaderboard shouldBe Seq(
          (bobUserParty, BigDecimal(0.6180000000)),
          (aliceUserParty, BigDecimal(0.2580000000)),
        )
      },
    )
  }
}
