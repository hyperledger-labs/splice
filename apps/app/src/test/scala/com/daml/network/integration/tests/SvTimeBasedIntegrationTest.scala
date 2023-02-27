package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.CoinUtil.defaultIssuanceCurve
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.math.RoundingMode

class SvTimeBasedIntegrationTest extends CoinIntegrationTest with WalletTestUtil with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

  "SVs collect SvcReward and SvReward automatically" in { implicit env =>
    eventually() {
      val rounds = getSortedOpenMiningRounds(svc.remoteParticipantWithAdminToken, svcParty)
      rounds should have size 3
      svs.map { sv =>
        val coins = sv.remoteParticipant.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv.getDebugInfo().svParty)
        coins shouldBe empty
      }
    }

    // one tick - round 0 closes.
    advanceRoundsByOneTick
    val config = defaultIssuanceCurve.currentValue
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
      getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 1

      // Only Sv1 get svc reward from round 0 as Sv2, Sv3 and Sv4 only joined in round 1
      inside(
        svs.head.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(svs.head.getDebugInfo().svParty)
      ) { case Seq(newCoin) =>
        newCoin.data.svc shouldBe svcParty.toProtoPrimitive
        newCoin.data.owner shouldBe svs.head.getDebugInfo().svParty.toProtoPrimitive
        newCoin.data.amount.initialAmount shouldBe coinsToIssueToSvc
          .setScale(10, RoundingMode.HALF_UP)
      }

      svs.tail.map { sv =>
        val coins = sv.remoteParticipant.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv.getDebugInfo().svParty)
        coins shouldBe empty
      }
    }

    // one tick - round 1 closes.
    advanceRoundsByOneTick
    eventually() {
      getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 2

      val eachSvGetInRound1 =
        coinsToIssueToSvc
          .divide(BigDecimal(svs.size).bigDecimal, RoundingMode.HALF_UP)
          .setScale(10, RoundingMode.HALF_UP)

      // All Svs get reward from round 1
      inside(
        svs.head.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(svs.head.getDebugInfo().svParty)
      ) { case Seq(_, newCoin) =>
        newCoin.data.svc shouldBe svcParty.toProtoPrimitive
        newCoin.data.owner shouldBe svs.head.getDebugInfo().svParty.toProtoPrimitive
        newCoin.data.amount.initialAmount shouldBe eachSvGetInRound1
      }

      svs.tail.map { sv =>
        inside(
          sv.remoteParticipantWithAdminToken.ledger_api_extensions.acs
            .filterJava(cc.coin.Coin.COMPANION)(sv.getDebugInfo().svParty)
        ) { case Seq(newCoin) =>
          newCoin.data.svc shouldBe svcParty.toProtoPrimitive
          newCoin.data.owner shouldBe sv.getDebugInfo().svParty.toProtoPrimitive
          newCoin.data.amount.initialAmount shouldBe eachSvGetInRound1
        }
      }
    }
  }
}
