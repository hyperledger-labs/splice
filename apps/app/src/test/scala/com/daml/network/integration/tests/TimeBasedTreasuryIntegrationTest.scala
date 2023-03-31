package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms.setPollingInterval
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTest
import com.daml.network.util.{CNNodeUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.HasExecutionContext
import org.slf4j.event.Level

import java.time.Duration

class TimeBasedTreasuryIntegrationTest
    extends CNNodeIntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        // for testing non-automation-based coin merging.
        setPollingInterval(NonNegativeFiniteDuration.ofSeconds(30))(config)
      )
  }

  "automatically merge transfer inputs when the automation is triggered" in { implicit env =>
    val (alice, bob) = onboardAliceAndBob()
    waitForWalletUser(aliceValidatorWallet)

    // create two coins in alice's wallet
    aliceWallet.tap(50)
    checkWallet(alice, aliceWallet, Seq(exactly(50)))

    // run a transfer such that alice's validator has some rewards
    p2pTransfer(aliceWalletBackend, aliceWallet, bobWallet, bob, 40.0)
    eventually()(aliceValidatorWallet.listAppRewardCoupons() should have size 1)
    eventually()(aliceValidatorWallet.listValidatorRewardCoupons() should have size 1)
    // and give alice another coin.
    aliceWallet.tap(50)
    checkWallet(alice, aliceWallet, Seq((9, 10), exactly(50)))

    // advance by two ticks, so the issuing round of round 1 is created
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    // advance time such that issuing round 1 is open to rewards collection.
    advanceRoundsByOneTick

    eventually()({
      // app rewards are automatically collected
      aliceValidatorWallet
        .listAppRewardCoupons()
        .filter(_.payload.round.number == 1) should have size 0
      // and coins are automatically merged.
      checkWallet(alice, aliceWallet, Seq((59, 61)))
      // same for validator rewards
      aliceValidatorWallet
        .listValidatorRewardCoupons()
        .filter(_.payload.round.number == 1) should have size 0
    })
  }

  "allow calling tap, list the created coins, and get the balance - locally and remotely" in {
    implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val aliceValidatorParty = aliceValidator.getValidatorPartyId()
      aliceWallet.tap(110)

      checkBalance(aliceWallet, 1, exactly(110), exactly(0), exactly(0))
      // leads to archival of open round 0
      advanceRoundsByOneTick

      lockCoins(
        aliceWalletBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWallet.list().coins,
        10,
        scan,
        Duration.ofDays(10),
      )
      checkBalance(
        aliceWallet,
        2,
        (99, 100),
        exactly(10),
        // due to merge in this round, no holding fees.
        exactly(0),
      )

      // leads to latest round being round 3
      advanceRoundsByOneTick

      checkBalance(
        aliceWallet,
        3,
        (99, 100),
        (9, 10),
        exactly(CNNodeUtil.defaultHoldingFee.rate),
      )
  }

  "don't collect rewards if their collection is more expensive than they reward in coins" in {
    implicit env =>
      val (_, bob) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWallet)

      // giving alice 2 coins...
      aliceWallet.tap(1)
      aliceWallet.tap(1)
      eventually() {
        aliceWallet.list().coins should have length 2
      }
      // ..so when she pays bob, she doesn't have to pay a transfer fee which
      // will result in alice validator's reward being small enough that its not worth it to collect the reward
      p2pTransfer(aliceWalletBackend, aliceWallet, bobWallet, bob, 0.00001)
      eventually() {
        aliceValidatorWallet.listAppRewardCoupons() should have length 1
        aliceValidatorWallet.listValidatorRewardCoupons() should have length 1
      }

      // advancing the rounds so the rewards would be collectable.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          // reward is now collectable..
          advanceRoundsByOneTick
        },
        entries => {
          forAtLeast(1, entries)( // however, we see that we choose not to the validator reward..
            _.message should include(
              "is smaller than the create-fee"
            )
          )
        },
      )

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          aliceValidatorWallet.tap(1)
          eventually() {
            aliceValidatorWallet.list().coins should have length 1
          }
          advanceTimeByPollingInterval(aliceWalletBackend)
        },
        entries => {
          forAtLeast(
            1,
            entries,
          )( // .. even when alice's validator has another coin and would only need to pay
            // an create-fee for collecting the reward.
            _.message should include(
              "is smaller than the create-fee"
            )
          )
        },
      )
  }

  "don't run merge if rewards and coins are too small" in { implicit env =>
    val (_, _) = onboardAliceAndBob()
    aliceWallet.tap(0.001)
    aliceWallet.tap(0.001)

    eventually() {
      aliceWallet.list().coins should have length 2
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      {
        // trigger automation.
        advanceRoundsByOneTick
      },
      entries => {
        forAtLeast(
          1,
          entries,
        )(
          // but do nothing since our coins are too small to be worth merging.
          _.message should include regex (
            "the total rewards and coin quantity .* is smaller than the create-fee"
          )
        )
      },
    )
  }

}
