package com.daml.network.integration.tests

import com.daml.network.config.CoinConfigTransforms
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level
import scala.annotation.nowarn

@nowarn("msg=match may not be exhaustive")
class TimeBasedTreasuryIntegrationTestWithoutMerging
    extends CoinIntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: CoinEnvironmentDefinition = {
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        // for testing that input limits are respected.
        CoinConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.disableAutomaticRewardsCollectionAndCoinMerging).replace(true)
        )(config)
      )
      .addConfigTransform((_, config) =>
        // for testing that input limits are respected.
        CoinConfigTransforms
          .updateAllSvAppConfigs_(_.focus(_.initialMaxNumInputs).replace(4))(config)
      )
  }

  "rewards from older rounds are prioritized while respecting maxNumInputs" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()

    aliceValidatorWallet.tap(100)
    p2pTransfer(
      aliceValidatorWallet,
      aliceWallet,
      alice,
      5,
    )

    advanceRoundsByOneTick

    eventually() {
      aliceValidatorWallet.list().coins should have length 1
      aliceValidatorWallet.listValidatorRewardCoupons() should have length 1
      aliceValidatorWallet.listAppRewardCoupons() should have length 1
    }

    // creating rewards in round 2
    p2pTransfer(
      aliceValidatorWallet,
      aliceWallet,
      alice,
      5,
    )
    aliceValidatorWallet.tap(50)

    eventually() {
      aliceValidatorWallet.list().coins should have length 2
      aliceValidatorWallet
        .listValidatorRewardCoupons()
        .filter(_.payload.round.number == 2) should have length 1
      aliceValidatorWallet
        .listAppRewardCoupons()
        .filter(_.payload.round.number == 2) should have length 1
    }

    // by advancing three rounds, both round 1 and round 2 are in their issuing phase.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    eventually() {
      aliceValidatorWallet.list().coins should have length 2
      aliceValidatorWallet
        .listValidatorRewardCoupons() should have length 2
      aliceValidatorWallet
        .listAppRewardCoupons() should have length 2
    }

    clue("rewards from round 1 are merged.") {
      // Note that the rewards from round 2 are not merged as transfers allow at most 4 inputs
      // and the rewards from round 1 are prioritized
      p2pTransfer(
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )
      eventually() {
        aliceValidatorWallet.list().coins should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
      }
    }

    clue("rewards from round 2 are merged") {
      p2pTransfer(
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )
      eventually() {
        // fails here when p2p-ing above.
        aliceValidatorWallet.list().coins should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
      }
    }
  }

  "more valuable rewards are prioritized while respecting maxNumInputs" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()

    aliceValidatorWallet.tap(10000)
    // Execute three transfers that generate different amount of rewards.
    p2pTransfer(
      aliceValidatorWallet,
      aliceWallet,
      alice,
      5,
    )
    // second and third transfer are a lot larger than the first one, but very close to each other.
    // because in the first part of the issuance curve already, apps (40%) gain a lot more rewards than validators (12%)
    // the app rewards, the app reward from the second transfer is prioritized over the validator reward from the
    // third (larger) transfer.
    p2pTransfer(
      aliceValidatorWallet,
      aliceWallet,
      alice,
      2000,
    )
    p2pTransfer(
      aliceValidatorWallet,
      aliceWallet,
      alice,
      2010,
    )

    // by advancing three rounds, round 1 is in the issuing phase.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    eventually() {
      aliceValidatorWallet.list().coins should have length 1
      aliceValidatorWallet.listValidatorRewardCoupons() should have length 3
      aliceValidatorWallet.listAppRewardCoupons() should have length 3
    }
    val Seq(vrew1, vrew2, _) =
      aliceValidatorWallet.listValidatorRewardCoupons().sortBy(_.payload.amount)
    val Seq(arew1, _, _) =
      aliceValidatorWallet.listAppRewardCoupons().sortBy(_.payload.amount)
    clue("most valuable rewards are merged first.") {
      p2pTransfer(
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )

      eventually() {
        // four inputs: 1 coin, 3 rewards.
        // only the most valuable validator reward is chosen as input because of the issuance curve.
        aliceValidatorWallet.list().coins should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1)
          .toList shouldBe Seq(vrew1, vrew2)
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1)
          .toList shouldBe Seq(arew1)
      }
    }

    clue("rest of the rewards are merged.") {
      // Note that the rewards from round 2 are not merged as transfers allow at most 4 inputs
      // and the rewards from round 1 are prioritized
      p2pTransfer(
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )

      eventually() {
        aliceValidatorWallet.list().coins should have length 1

        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
      }
    }
  }

  "adjust maxNumInput if there is a tap operation in the batch" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()
    aliceValidatorWallet.tap(50)
    p2pTransfer(
      aliceValidatorWallet,
      aliceWallet,
      alice,
      5,
    )
    aliceValidatorWallet.tap(50)

    eventually() {
      aliceValidatorWallet.list().coins should have length 2
      aliceValidatorWallet.listValidatorRewardCoupons() should have length 1
      aliceValidatorWallet.listAppRewardCoupons() should have length 1
    }

    // advancing three rounds so the rewards are collectable.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      {
        aliceValidatorWallet.tap(1)
        eventually() {
          aliceValidatorWallet.list().coins should have length 3
          aliceValidatorWallet.listValidatorRewardCoupons() should have length 1
          aliceValidatorWallet.listAppRewardCoupons() should have length 1
        }
      },
      entries => {
        forAtLeast(
          1,
          entries,
        )(
          // if we run a tap, only 3 of 4 possible inputs are selected because one input slot is "taken" by the tap
          // (notice how the app reward coupon is not an input)
          _.message should include regex (
            "with inputs List\\(InputCoin\\(.*\\), InputCoin\\(.*\\), InputAppRewardCoupon\\(.*\\)\\)"
          )
        )
      },
    )

  }
}
