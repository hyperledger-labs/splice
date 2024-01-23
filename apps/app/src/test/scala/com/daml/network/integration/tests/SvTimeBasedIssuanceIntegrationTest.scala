package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.codegen.java.cc.coin.{AppRewardCoupon, ValidatorRewardCoupon}
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.util.CNNodeUtil.defaultIssuanceCurve
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*
import java.math.RoundingMode

class SvTimeBasedIssuanceIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithSharedEnvironment {
  "SVs collect SvcReward and SvReward automatically" in { implicit env =>
    eventually() {
      val rounds = getSortedOpenMiningRounds(sv1Backend.participantClientWithAdminToken, svcParty)
      rounds should have size 3
      svs.map { sv =>
        val coins = sv.participantClient.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv.getSvcInfo().svParty)
        coins shouldBe empty
      }
    }

    // one tick - round 0 closes.
    advanceRoundsByOneTick
    val config = defaultIssuanceCurve.initialValue
    val RoundsPerYear =
      BigDecimal(365 * 24 * 60 * 60).bigDecimal.divide(BigDecimal(150.0).bigDecimal)
    val coinsToIssueToSvc = config.coinToIssuePerYear
      .multiply(
        BigDecimal(1.0).bigDecimal
          .subtract(config.appRewardPercentage)
          .subtract(config.validatorRewardPercentage)
      )
      .divide(RoundsPerYear, RoundingMode.HALF_UP)
    eventually() {
      getSortedIssuingRounds(
        sv1Backend.participantClientWithAdminToken,
        svcParty,
      ) should have size 1

      // All of SV1 to SV4 join as of round 0 and get a reward
      val eachSvGetInRound0 =
        coinsToIssueToSvc
          .divide(BigDecimal(svs.size).bigDecimal, RoundingMode.HALF_UP)
          .setScale(10, RoundingMode.HALF_UP)

      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map { sv =>
        inside(
          sv.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cc.coin.Coin.COMPANION)(sv.getSvcInfo().svParty)
        ) { case Seq(newCoin) =>
          newCoin.data.svc shouldBe svcParty.toProtoPrimitive
          newCoin.data.owner shouldBe sv.getSvcInfo().svParty.toProtoPrimitive
          newCoin.data.amount.initialAmount shouldBe eachSvGetInRound0
        }
      }
    }

    clue("SvReward collection works even if some rewards are too old to collect") {
      val sv4Party = sv4Backend.getSvcInfo().svParty
      clue("Stopping SV4 so it can't collect its rewards") {
        sv4Backend.stop()
      }
      val (_, existingCoins) = actAndCheck(
        "Advancing by 12 rounds",
        (1 to 11).foreach(_ => advanceRoundsByOneTick),
      )(
        "SV4 has 11 unclaimed rewards contracts",
        _ => {
          sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svcrules.SvReward.COMPANION)(
              svcParty
            )
            .filter(_.data.sv == sv4Party.toProtoPrimitive) should have length 11
          val coinsSet = sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cc.coin.Coin.COMPANION)(sv4Party)
            .map(_.id)
            .toSet
          coinsSet should have size 1
          coinsSet
        },
      )
      actAndCheck()(
        "SV4 starts again",
        sv4Backend.startSync(),
      )(
        "SV4 collects all rewards it can and leaves the one it can't",
        _ => {
          sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svcrules.SvReward.COMPANION)(
              svcParty
            ) should have length 1
          // excluded existing coins
          sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cc.coin.Coin.COMPANION)(
              sv4Party,
              c => !existingCoins.contains(c.id),
            ) should have length 10
        },
      )
      actAndCheck(
        "We advance by another round",
        advanceRoundsByOneTick,
      )(
        // TODO(#5059) Implement expiry of SV rewards that are too old
        "The new reward gets collected by SV4, the old ones still remain (until we implement expiry)",
        _ => {
          sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svcrules.SvReward.COMPANION)(
              svcParty
            )
            .filter(_.data.sv == sv4Party.toProtoPrimitive) should have length 1

          // excluded existing coins
          sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cc.coin.Coin.COMPANION)(
              sv4Party,
              c => !existingCoins.contains(c.id),
            ) should have length 11
        },
      )
    }
  }

  "calculation of issuance per coin" in { implicit env =>
    val latestIssueRound = clue("Ensure there is at least one issuing round") {
      eventually() {
        getSortedIssuingRounds(
          sv1Backend.participantClientWithAdminToken,
          svcParty,
        ).lastOption.getOrElse {
          logger.info("No issuing round found -- advancing rounds by one tick")
          advanceRoundsByOneTick
          fail("No issuing round found")
        }
      }
    }
    val currentRoundNum = latestIssueRound.data.round.number.toLong + 1L

    // 3 unfeatured app rewards & 3 featured app rewards & 3 validator rewards, 2 of each for round 0 and one for round 1
    // to check we sum up but only for the right round.
    val rewards = Seq(
      // featured app rewards for a total of 200.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(1.0).bigDecimal,
        new Round(currentRoundNum),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(199.0).bigDecimal,
        new Round(currentRoundNum),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(3.0).bigDecimal,
        new Round(currentRoundNum + 1L),
      ),
      // unfeatured app rewards for a total of 9800.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(2.5).bigDecimal,
        new Round(currentRoundNum),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(9797.5).bigDecimal,
        new Round(currentRoundNum),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(5.0).bigDecimal,
        new Round(currentRoundNum + 1L),
      ),
      // validator rewards for a total of 10000.0 in round 0
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(3.0).bigDecimal,
        new Round(currentRoundNum),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(9997.0).bigDecimal,
        new Round(currentRoundNum),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(15.0).bigDecimal,
        new Round(currentRoundNum + 1L),
      ),
    )
    // Create a bunch of rewards directly
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands = rewards.flatMap(_.create.commands.asScala.toSeq),
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        advanceRoundsByOneTick
        eventually() {
          val latestIssueRoundAfter1Tick = getSortedIssuingRounds(
            sv1Backend.participantClientWithAdminToken,
            svcParty,
          ).lastOption.value
          latestIssueRoundAfter1Tick.data.round.number shouldBe currentRoundNum
        }
      },
      entries =>
        // we should expect logs of creating confirmation by at least 3 SV which is the required number of confirmations.
        forAtLeast(3, entries)(
          _.message should include(
            // TODO(#8819): also test for validator faucet coupons once they are summarized as well
            // TODO(#9173): also test for SV reward coupons once they are summarized as well
            s"created confirmation for summarizing mining round with com.daml.network.codegen.java.cc.issuance.OpenMiningRoundSummary(10000.0000000000, 200.0000000000, 9800.0000000000, 0, Optional.empty)"
          )
        ),
    )

    def decimal(d: Double): java.math.BigDecimal = BigDecimal(d).setScale(10).bigDecimal

    val issuingRound =
      getSortedIssuingRounds(sv1Backend.participantClientWithAdminToken, svcParty).lastOption.value

    issuingRound.data.issuancePerValidatorRewardCoupon shouldBe decimal(0.2000000000)
    issuingRound.data.issuancePerFeaturedAppRewardCoupon shouldBe decimal(100.0000000000)
    issuingRound.data.issuancePerUnfeaturedAppRewardCoupon shouldBe decimal(0.6000000000)
  }

  "collect expired reward coupons" in { implicit env =>
    def getRewardCoupons(
        round: Contract[OpenMiningRound.ContractId, OpenMiningRound]
    ) = {
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(AppRewardCoupon.COMPANION)(
          svcParty,
          co => co.data.round.number == round.payload.round.number,
        ) ++
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorRewardCoupon.COMPANION)(
            svcParty,
            co => co.data.round.number == round.payload.round.number,
          )
    }

    startAllSync(aliceValidatorBackend, bobValidatorBackend)

    val round =
      sv1ScanBackend.getTransferContextWithInstances(getLedgerTime).latestOpenMiningRound.contract
    // There may be rewards left over from other tests, so we first check the
    // contract IDs of existing ones, and compare to that below
    val leftoverRewardIds = getRewardCoupons(round).view.map(_.id).toSet

    val (aliceParty, bobParty) = onboardAliceAndBob()
    aliceWalletClient.tap(100.0)
    bobWalletClient.tap(100.0)

    actAndCheck(
      "Generate some reward coupons by executing a few direct transfers", {
        p2pTransfer(aliceWalletClient, bobWalletClient, bobParty, 10.0)
        p2pTransfer(aliceWalletClient, bobWalletClient, bobParty, 10.0)
        p2pTransfer(bobWalletClient, aliceWalletClient, aliceParty, 10.0)
        p2pTransfer(bobWalletClient, aliceWalletClient, aliceParty, 10.0)
      },
    )(
      "Wait for all reward coupons to be created",
      _ => {
        advanceTimeByPollingInterval(sv1Backend)
        getRewardCoupons(round)
          .filterNot(c =>
            leftoverRewardIds(c.id)
          ) should have length 8 // 4 app rewards + 4 validator
      },
    )

    actAndCheck(
      "Advance 5 ticks, to close the round",
      (1 to 5).foreach(_ => advanceRoundsByOneTick),
    )(
      "Wait for all unclaimed coupons to be archived and the closed round to be archived",
      _ => {
        advanceTimeByPollingInterval(sv1Backend)
        getRewardCoupons(round) shouldBe empty
        sv1ScanBackend
          .getClosedRounds()
          .filter(r => r.payload.round.number == round.payload.round.number) should be(empty)
      },
    )
  }

}
