package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin.{AppRewardCoupon, ValidatorRewardCoupon}
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.util.CNNodeUtil.defaultIssuanceCurve
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*
import java.math.RoundingMode

class SvTimeBasedIssuanceIntegrationTest extends SvTimeBasedIntegrationTestBase {
  "SVs collect SvcReward and SvReward automatically" in { implicit env =>
    initSvc()

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

      // Only Sv1 get svc reward from round 0 as Sv2, Sv3 and Sv4 only joined in round 3
      inside(
        svs.head.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(svs.head.getSvcInfo().svParty)
      ) { case Seq(newCoin) =>
        newCoin.data.svc shouldBe svcParty.toProtoPrimitive
        newCoin.data.owner shouldBe svs.head.getSvcInfo().svParty.toProtoPrimitive
        newCoin.data.amount.initialAmount shouldBe coinsToIssueToSvc
          .setScale(10, RoundingMode.HALF_UP)
      }

      Seq(sv2Backend, sv3Backend, sv4Backend).map { sv =>
        val coins = sv.participantClient.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv.getSvcInfo().svParty)
        coins shouldBe empty
      }
    }

    // three ticks - rounds 1-3 close.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    eventually() {
      getSortedIssuingRounds(
        sv1Backend.participantClientWithAdminToken,
        svcParty,
      ) should have size 3

      val eachSvGetInRound3 =
        coinsToIssueToSvc
          .divide(BigDecimal(svs.size).bigDecimal, RoundingMode.HALF_UP)
          .setScale(10, RoundingMode.HALF_UP)

      // All Svs get reward from round 3
      inside(
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(svs.head.getSvcInfo().svParty)
      ) { case Seq(_, _, _, newCoin) =>
        newCoin.data.svc shouldBe svcParty.toProtoPrimitive
        newCoin.data.owner shouldBe sv1Backend.getSvcInfo().svParty.toProtoPrimitive
        newCoin.data.amount.initialAmount shouldBe eachSvGetInRound3
      }

      Seq(sv2Backend, sv3Backend, sv4Backend).map { sv =>
        inside(
          sv.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cc.coin.Coin.COMPANION)(sv.getSvcInfo().svParty)
        ) { case Seq(newCoin) =>
          newCoin.data.svc shouldBe svcParty.toProtoPrimitive
          newCoin.data.owner shouldBe sv.getSvcInfo().svParty.toProtoPrimitive
          newCoin.data.amount.initialAmount shouldBe eachSvGetInRound3
        }
      }
    }
  }

  "SvReward collection works even if some rewards are too old to collect" in { implicit env =>
    initSvc()
    val sv4Party = sv4Backend.getSvcInfo().svParty
    clue("Stopping SV4 so it can't collect its rewards") {
      sv4Backend.stop()
    }
    actAndCheck(
      "Advancing by 15 rounds",
      (1 to 14).foreach(_ => advanceRoundsByOneTick),
    )(
      "SV4 has 11 unclaimed rewards contracts",
      _ => {
        sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvReward.COMPANION)(
            svcParty
          )
          .filter(_.data.sv == sv4Party.toProtoPrimitive) should have length 11
        sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv4Party) should have length 0
      },
    )
    actAndCheck(
      "SV4 starts again",
      sv4Backend.startSync(),
    )(
      "SV4 collects all rewards it can and leaves the one it can't",
      _ => {
        sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvReward.COMPANION)(
            svcParty
          ) should have length 1
        sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv4Party) should have length 10
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
          ) should have length 1
        sv4Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv4Party) should have length 11
      },
    )
  }

  "calculation of issuance per coin" in { implicit env =>
    initSvc()

    // 3 unfeatured app rewards & 3 featured app rewards & 3 validator rewards, 2 of each for round 0 and one for round 1
    // to check we sum up but only for the right round.
    val rewards = Seq(
      // featured app rewards for a total of 200.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(1.0).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(199.0).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(3.0).bigDecimal,
        new Round(1),
      ),
      // unfeatured app rewards for a total of 9800.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(2.5).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(9797.5).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(5.0).bigDecimal,
        new Round(1),
      ),
      // validator rewards for a total of 10000.0 in round 0
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(3.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(9997.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(15.0).bigDecimal,
        new Round(1),
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
          getSortedIssuingRounds(
            sv1Backend.participantClientWithAdminToken,
            svcParty,
          ) should have size 1
        }
      },
      entries =>
        // we should expect logs of creating confirmation by at least 3 SV which is the required number of confirmations.
        forAtLeast(3, entries)(
          _.message should include(
            s"created confirmation for summarizing mining round with com.daml.network.codegen.java.cc.issuance.OpenMiningRoundSummary(10000.0000000000, 200.0000000000, 9800.0000000000)"
          )
        ),
    )

    def decimal(d: Double): java.math.BigDecimal = BigDecimal(d).setScale(10).bigDecimal

    val issuingRounds = getSortedIssuingRounds(sv1Backend.participantClientWithAdminToken, svcParty)

    inside(issuingRounds) { case Seq(issuingRound) =>
      issuingRound.data.issuancePerValidatorRewardCoupon shouldBe decimal(0.2000000000)
      issuingRound.data.issuancePerFeaturedAppRewardCoupon shouldBe decimal(100.0000000000)
      issuingRound.data.issuancePerUnfeaturedAppRewardCoupon shouldBe decimal(0.6000000000)
    }
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

    initSvc()
    startAllSync(aliceValidatorBackend, bobValidatorBackend)

    val round =
      sv1ScanBackend.getTransferContextWithInstances(getLedgerTime).latestOpenMiningRound.contract
    // There may be rewards left over from other tests, so we first check the
    // contract IDs of existing ones, and compare to that below
    val leftoverRewardIds = getRewardCoupons(round).view.map(_.id).toSet

    val (aliceParty, bobParty) = onboardAliceAndBob()
    aliceWallet.tap(100.0)
    bobWalletClient.tap(100.0)

    actAndCheck(
      "Generate some reward coupons by executing a few direct transfers", {
        p2pTransfer(aliceValidatorBackend, aliceWallet, bobWalletClient, bobParty, 10.0)
        p2pTransfer(aliceValidatorBackend, aliceWallet, bobWalletClient, bobParty, 10.0)
        p2pTransfer(bobValidatorBackend, bobWalletClient, aliceWallet, aliceParty, 10.0)
        p2pTransfer(bobValidatorBackend, bobWalletClient, aliceWallet, aliceParty, 10.0)
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
