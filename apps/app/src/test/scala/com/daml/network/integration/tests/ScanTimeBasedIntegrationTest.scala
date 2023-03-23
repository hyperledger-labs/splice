package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil, CNNodeUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.console.WalletAppClientReference
import scala.jdk.CollectionConverters.*
import com.daml.network.util.Codec
import com.digitalasset.canton.topology.PartyId

class ScanTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndCoinMerging

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
        CNNodeUtil.defaultCreateFee.fee.setScale(10)
      )
      cfg.holdingFee.bigDecimal.setScale(10) should be(
        CNNodeUtil.defaultHoldingFee.rate.setScale(10)
      )
      cfg.lockHolderFee.bigDecimal.setScale(10) should be(
        CNNodeUtil.defaultLockHolderFee.fee.setScale(10)
      )
      cfg.transferFee.initial.bigDecimal.setScale(10) should be(
        CNNodeUtil.defaultTransferFee.initialRate.setScale(10)
      )
      cfg.transferFee.steps shouldBe (
        CNNodeUtil.defaultTransferFee.steps.asScala.toSeq.map(step =>
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
      bobValidatorWallet.tap(100.0)
    }

    clue("Transfer some CC, to generate reward coupons")({
      p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bobUserParty, 40.0)
      p2pTransferAndTriggerAutomation(bobWallet, aliceWallet, aliceUserParty, 100.0)
    })
    clue(
      "Advance a round and generate some more reward coupons - this time with alice's validator being featured"
    )({
      advanceRoundsByOneTick
      grantFeaturedAppRight(aliceValidatorWallet)
      p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bobUserParty, 41.0)
      p2pTransferAndTriggerAutomation(bobWallet, aliceWallet, aliceUserParty, 101.0)
    })
    clue("Advance 2 ticks for the first coupons to be collectable")({
      advanceRoundsByOneTick
      advanceRoundsByOneTick
    })
    clue("Alice's and Bob's validators use their app&validator rewards when transfering CC")({
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
    clue("Some more transfers collect more rewards in round 5 (issued in round 1)")({
      p2pTransferAndTriggerAutomation(aliceValidatorWallet, bobWallet, bobUserParty, 10.0)
      p2pTransferAndTriggerAutomation(bobValidatorWallet, aliceWallet, aliceUserParty, 10.0)
    })

    clue("Data for a later round does not yet exist")({
      assertThrowsAndLogsCommandFailures(
        scan.getTopProvidersByAppRewards(4, 10),
        _.errorMessage should include("Data for round 4 not yet computed"),
      )
      assertThrowsAndLogsCommandFailures(
        scan.getTopValidatorsByValidatorRewards(4, 10),
        _.errorMessage should include("Data for round 4 not yet computed"),
      )
    })
    def compareLeaderboard(
        result: Seq[(PartyId, BigDecimal)],
        expected: Seq[(WalletAppClientReference, BigDecimal)],
    ) = {
      result shouldBe expected.map((v) =>
        (Codec.decode(Codec.Party)((v._1.userStatus().party)).value, v._2)
      )
    }

    actAndCheck(
      "Advance five more rounds, for rounds 4&5 to close (where rewards were collected)",
      Range(0, 5).foreach(_ => advanceRoundsByOneTick),
    )(
      "Test leaderboards for ends of rounds 4&5",
      _ => {
        eventually() {
          scan.getRoundOfLatestData() should be(5)
        }

        // TODO(#2930): consider de-hard-coding the expected values here somehow, e.g. by only checking them relative to each other
        compareLeaderboard(
          scan.getTopProvidersByAppRewards(4, 10),
          Seq(
            (bobValidatorWallet, BigDecimal(0.6180000000)),
            (aliceValidatorWallet, BigDecimal(0.2580000000)),
          ),
        )
        compareLeaderboard(
          scan.getTopValidatorsByValidatorRewards(4, 10),
          Seq(
            (bobValidatorWallet, BigDecimal(0.2060000000)),
            (aliceValidatorWallet, BigDecimal(0.0860000000)),
          ),
        )
        compareLeaderboard(
          scan.getTopProvidersByAppRewards(5, 10),
          Seq(
            (aliceValidatorWallet, BigDecimal(44.2580000000)),
            (bobValidatorWallet, BigDecimal(1.2366000000)),
          ),
        )
        compareLeaderboard(
          scan.getTopValidatorsByValidatorRewards(5, 10),
          Seq(
            (bobValidatorWallet, BigDecimal(0.4122000000)),
            (aliceValidatorWallet, BigDecimal(0.1740000000)),
          ),
        )
      },
    )
  }
}
