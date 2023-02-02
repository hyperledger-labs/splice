package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletRewardsTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

  "A wallet" should {

    "list and manually collect app & validator rewards" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()

      // Tap coin and do a transfer from alice to bob
      aliceWallet.tap(50)

      p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bob, 40.0)

      // Retrieve transferred coin in bob's wallet and transfer part of it back to alice; bob will receive some app rewards
      eventually()(bobWallet.list().coins should have size 1)
      p2pTransferAndTriggerAutomation(bobWallet, aliceWallet, alice, 30.0)

      eventually() {
        bobWallet.listAppRewardCoupons() should have size 1
      }
      bobWallet.listValidatorRewardCoupons() shouldBe empty

      eventually() {
        // Wait for validator rewards to become visible in alice's wallet, check structure
        aliceValidatorWallet.listValidatorRewardCoupons() should have size 1
      }

      val prevCoins = bobWallet.list().coins

      // Bob collects/realizes rewards
      // it takes 3 ticks for IR 1 to be created and open.
      advanceRoundsByOneTick
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      eventually()(bobWallet.listAppRewardCoupons() should have size 0)
      bobWallet.listValidatorRewardCoupons() should have size 0
      // We just check that we have a coin roughly in the right range, in particular higher than the input, rather than trying to repeat the calculation
      // for rewards.
      checkWallet(
        bob,
        bobWallet,
        prevCoins
          .map(c =>
            (
              BigDecimal(c.contract.payload.amount.initialAmount),
              BigDecimal(c.contract.payload.amount.initialAmount) + 2,
            )
          )
          .sortBy(_._1),
      )
    }
  }
}
