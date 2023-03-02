package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.api.v1.round.Round
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

import java.time.Duration
import scala.jdk.CollectionConverters.*

class SvcTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => {
        // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the svc to consider unclaimed
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableAutomaticRewardsCollectionAndCoinMerging).replace(false)
        )(config)
        // TODO(M3-63) Currently, auto-expiration of unclaimed rewards is disabled by default, and enabled only where needed.
        // In the cluster it currently cannot be enabled due to lack of resiliency to unavailable validators
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableUnclaimedRewardExpiration).replace(true)
        )(config)
      })

  "calculation of issuance per coin" in { implicit env =>
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
    svc.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands = rewards.flatMap(_.create.commands.asScala.toSeq),
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        advanceRoundsByOneTick
        eventually() {
          getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 1
        }
      },
      entries =>
        forAtLeast(1, entries)(
          _.message should include(
            s"completed summarizing mining round with com.daml.network.codegen.java.cc.issuance.OpenMiningRoundSummary(10000.0000000000, 200.0000000000, 9800.0000000000)"
          )
        ),
    )

    def decimal(d: Double): java.math.BigDecimal = BigDecimal(d).setScale(10).bigDecimal

    val issuingRounds = getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty)

    inside(issuingRounds) { case Seq(issuingRound) =>
      issuingRound.data.issuancePerValidatorRewardCoupon shouldBe decimal(0.2000000000)
      issuingRound.data.issuancePerFeaturedAppRewardCoupon shouldBe decimal(100.0000000000)
      issuingRound.data.issuancePerUnfeaturedAppRewardCoupon shouldBe decimal(0.6000000000)
    }
  }

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
        advanceTime(Duration.ofSeconds(1))
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
