package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.WalletAppClientReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.util.*
import com.daml.network.util.CNNodeUtil.defaultCnsConfig
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

class ScanTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransformsToFront(
        { case (_, c) => CNNodeConfigTransforms.ingestFromParticipantBeginInScan(c) }
      )
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndCoinMerging

  "report correct reference data" in { implicit env =>
    def roundNum() =
      sv1ScanBackend.getLatestOpenMiningRound(getLedgerTime).contract.payload.round.number
    roundNum() shouldBe 1

    advanceRoundsByOneTick
    roundNum() shouldBe 2
  }

  "return correct coin configs" in { implicit env =>
    // TODO(#2930) test also with changing coin prices.
    // TODO(#2930) Currently we are not guaranteed that the first three rounds are correctly
    // captured in the tx log, so for now we first advance a round, and query only on that
    // round and beyond. Once that is fixed, we should make sure that querying for round 0 is reliable as well.

    advanceRoundsByOneTick

    clue("Get config for round 3") {
      val cfg = eventuallySucceeds() {
        sv1ScanBackend.getCoinConfigForRound(3)
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
        sv1ScanBackend.getCoinConfigForRound(4),
        _.errorMessage should include("Round 4 not found"),
      )
    }

    val newHoldingFee = 0.1
    clue("schedule a config change, and advance time for it to take effect") {
      val currentConfigSchedule = sv1ScanBackend.getCoinRules().contract.payload.configSchedule
      val configSchedule =
        createConfigSchedule(
          currentConfigSchedule,
          (
            defaultTickDuration.asJava,
            mkUpdatedCoinConfig(
              currentConfigSchedule,
              tickDuration = defaultTickDuration,
              holdingFee = newHoldingFee,
            ),
          ),
        )

      setFutureConfigSchedule(configSchedule)

      advanceRoundsByOneTick
    }
    clue("Round 4 should now be open, and have the new configuration") {
      eventuallySucceeds() {
        sv1ScanBackend.getCoinConfigForRound(4).holdingFee should be(newHoldingFee)
      }
    }
  }

  "support app and validator leaderboards" in { implicit env =>
    val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
    waitForWalletUser(aliceValidatorWalletClient)
    waitForWalletUser(bobValidatorWalletClient)

    clue("Tap to get some coins") {
      aliceWalletClient.tap(500.0)
      bobWalletClient.tap(500.0)
      aliceValidatorWalletClient.tap(100.0)
      bobValidatorWalletClient.tap(100.0)
    }
    clue("No aggregate round data should be available yet")({
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.getRoundOfLatestData(),
        _.errorMessage should include("No data has been made available yet"),
      )
    })
    clue("Transfer some CC, to generate reward coupons")({
      p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 40.0)
      p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, 100.0)
    })
    clue(
      "Advance a round and generate some more reward coupons - this time with alice's validator being featured"
    )({
      advanceRoundsByOneTick
      grantFeaturedAppRight(aliceValidatorWalletClient)
      p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 41.0)
      p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, 101.0)
    })
    clue("Advance 2 ticks for the first coupons to be collectable")({
      advanceRoundsByOneTick
      advanceRoundsByOneTick
    })
    clue("Alice's and Bob's validators use their app&validator rewards when transfering CC")({
      p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0)
      p2pTransfer(bobValidatorWalletClient, aliceWalletClient, aliceUserParty, 10.0)
    })
    clue("Some more transfers collect more rewards in round 5 (issued in round 1)")({
      advanceRoundsByOneTick
      p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0)
      p2pTransfer(bobValidatorWalletClient, aliceWalletClient, aliceUserParty, 10.0)
    })
    val baseRoundWithLatestData = clue(
      "Advance 1 more tick to make sure we capture at least one round change in the tx history"
    ) {
      advanceRoundsByOneTick
      sv1ScanBackend.automation.store.aggregate().futureValue
      sv1ScanBackend.getRoundOfLatestData()._1
    }
    clue("Advance one more tick to get to the next closed round") {
      advanceRoundsByOneTick
      val ledgerTime = getLedgerTime.toInstant
      sv1ScanBackend.automation.store.aggregate().futureValue
      sv1ScanBackend.getRoundOfLatestData() should be((baseRoundWithLatestData + 1, ledgerTime))
    }
    clue("Data for a later round does not yet exist")({
      val laterRound = baseRoundWithLatestData + 2
      sv1ScanBackend.getTopProvidersByAppRewards(laterRound, 10) shouldBe empty
      sv1ScanBackend.getTopValidatorsByValidatorRewards(laterRound, 10) shouldBe empty
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
      "Advance four more rounds, for the previous rounds to close (where rewards were collected)",
      Range(0, 4).foreach(_ => advanceRoundsByOneTick),
    )(
      "Test leaderboards for ends of rounds 4 and 5",
      _ => {
        val ledgerTime = getLedgerTime.toInstant
        sv1ScanBackend.automation.store.aggregate().futureValue
        sv1ScanBackend.getRoundOfLatestData() should be((baseRoundWithLatestData + 5, ledgerTime))

        // TODO(#2930): consider de-hard-coding the expected values here somehow, e.g. by only checking them relative to each other
        val appRewardsBobR3 = BigDecimal(0.6180000000)
        val appRewardsAliceR3 = BigDecimal(0.2580000000)
        val validatorRewardsBobR3 = BigDecimal(0.2060000000)
        val validatorRewardsAliceR3 = BigDecimal(0.0860000000)

        (0 to baseRoundWithLatestData.toInt + 3).map { round =>
          sv1ScanBackend.getRewardsCollectedInRound(round.toLong)
        }.sum shouldBe appRewardsAliceR3 + appRewardsBobR3 + validatorRewardsAliceR3 + validatorRewardsBobR3

        clue("Compare leaderboard getTopProvidersByAppRewards + 3") {
          compareLeaderboard(
            sv1ScanBackend.getTopProvidersByAppRewards(baseRoundWithLatestData + 3, 10),
            Seq(
              (bobValidatorWalletClient, appRewardsBobR3),
              (aliceValidatorWalletClient, appRewardsAliceR3),
            ),
          )
        }
        clue("Compare leaderboard getTopValidatorsByValidatorRewards + 3") {
          compareLeaderboard(
            sv1ScanBackend.getTopValidatorsByValidatorRewards(baseRoundWithLatestData + 3, 10),
            Seq(
              (bobValidatorWalletClient, validatorRewardsBobR3),
              (aliceValidatorWalletClient, validatorRewardsAliceR3),
            ),
          )
        }
        clue("Compare leaderboard getTopProvidersByAppRewards + 4") {
          compareLeaderboard(
            sv1ScanBackend.getTopProvidersByAppRewards(baseRoundWithLatestData + 4, 10),
            Seq(
              (aliceValidatorWalletClient, BigDecimal(44.2580000000)),
              (bobValidatorWalletClient, BigDecimal(1.2366000000)),
            ),
          )
        }
        clue("Compare leaderboard getTopValidatorsByValidatorRewards + 4") {
          compareLeaderboard(
            sv1ScanBackend.getTopValidatorsByValidatorRewards(baseRoundWithLatestData + 4, 10),
            Seq(
              (bobValidatorWalletClient, BigDecimal(0.4122000000)),
              (aliceValidatorWalletClient, BigDecimal(0.1740000000)),
            ),
          )
        }
      },
    )
  }

  "get total coin balance" in { implicit env =>
    val (_, _) = onboardAliceAndBob()

    def roundNum() =
      sv1ScanBackend.getLatestOpenMiningRound(getLedgerTime).contract.payload.round.number
    roundNum() shouldBe 1

    val tapRound1Amount = BigDecimal(500.0)
    clue("Tap in round 1") {
      aliceWalletClient.tap(tapRound1Amount)
    }

    advanceRoundsByOneTick

    roundNum() shouldBe 2

    val tapRound2Amount = BigDecimal(500.0)
    clue("Tap in round 2") {
      bobWalletClient.tap(tapRound2Amount)
    }

    actAndCheck(
      "advance to close round 2",
      (0 to 5).foreach(_ => advanceRoundsByOneTick),
    )(
      "check round 2 is closed",
      _ => {
        sv1ScanBackend.getRoundOfLatestData()._1 shouldBe 2
      },
    )

    clue("Get total balances for round 0, 1 and 2") {
      val holdingFee = BigDecimal(CNNodeUtil.defaultHoldingFee.rate)
      val total0 = sv1ScanBackend.getTotalCoinBalance(0)
      val total1 = sv1ScanBackend.getTotalCoinBalance(1)
      val total2 = sv1ScanBackend.getTotalCoinBalance(2)

      val holdingFeeAfterOneRound = 1 * holdingFee
      val holdingFeeAfterTwoRounds = 2 * holdingFee

      total0 shouldBe 0.0
      total1 shouldBe (tapRound1Amount - holdingFeeAfterOneRound)
      total2 shouldBe (
        tapRound1Amount - holdingFeeAfterTwoRounds +
          tapRound2Amount - holdingFeeAfterOneRound
      )
    }
  }

  "return CnsRules contract and config" in { implicit env =>
    val cnsRules = sv1ScanBackend.getCnsRules()
    cnsRules.payload.config shouldBe defaultCnsConfig()
  }
}
