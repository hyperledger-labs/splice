package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.amulet.{Amulet, SvRewardCoupon, UnclaimedReward}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.CNNodeUtil.defaultIssuanceCurve
import com.daml.network.util.{SvTestUtil, TimeTestUtil, WalletTestUtil}
import com.daml.network.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule.LevelAndAbove
import org.slf4j.event.Level

class DisabledWalletTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with SvTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, conf) =>
        conf.copy(validatorApps = conf.validatorApps.map {
          case (name, validatorConf) if name.unwrap == "sv1Validator" =>
            name -> validatorConf.copy(enableWallet = false)
          case x => x
        })
      )
      .withManualStart

  "Disabled wallet" in { implicit env =>
    clue("Start apps, wallet automations should not start up") {
      loggerFactory.assertLogsSeq(LevelAndAbove(Level.DEBUG))(
        startAllSync(sv1Backend, sv1ValidatorBackend, sv1ScanBackend),
        logs => {
          inside(logs) { case logs =>
            logs.exists(log =>
              log.loggerName.matches(
                s".*${classOf[TreasuryService].getSimpleName}.*SV=sv1.*"
              ) || log.loggerName.matches(
                s".*${classOf[CollectRewardsAndMergeAmuletsTrigger].getSimpleName}.*SV=sv1.*"
              )
            ) should be(false)
          }
        },
      )
    }

    clue("HTTP server should not start up") {
      assertThrowsAndLogsCommandFailures(
        sv1WalletClient.list() should be(1),
        log =>
          log.message should include(
            "Command failed, message: The requested resource could not be found."
          ),
      )
    }

    clue("Rewards should end up in unclaimed rewards pool") {
      var currentRound =
        sv1ScanBackend.getOpenAndIssuingMiningRounds()._1.head.contract.payload.round.number
      val expectedMinAmount =
        BigDecimal(
          computeSvRewardInRound0(
            defaultIssuanceCurve.initialValue,
            defaultTickDuration,
            dsoSize = 1,
          )
        ) - smallAmount

      eventually() {
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(SvRewardCoupon.COMPANION)(
            dsoParty,
            coupon => {
              coupon.data.sv == sv1Backend.getDsoInfo().svParty.toProtoPrimitive &&
              coupon.data.round.number == currentRound
            },
          ) should not be empty

        advanceRoundsByOneTick
        currentRound += 1

        val unclaimed = sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedReward.COMPANION)(
            dsoParty,
            reward => BigDecimal(reward.data.amount) >= expectedMinAmount,
          )
        unclaimed should not be empty

        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(Amulet.COMPANION)(dsoParty, _ => true) shouldBe empty
      }
    }

  }

}
