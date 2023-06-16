package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletRewardsTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyXCentralizedDomainWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )

  "A wallet" should {

    "list and automatically collect app & validator rewards" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWallet)
      waitForWalletUser(bobValidatorWallet)

      // Tap coin and do a transfer from alice to bob
      aliceWallet.tap(50)

      p2pTransfer(aliceValidator, aliceWallet, bobWallet, bob, 40.0)

      // Retrieve transferred coin in bob's wallet and transfer part of it back to alice;
      // bob's validator will receive some app rewards
      eventually()(bobWallet.list().coins should have size 1)
      p2pTransfer(bobValidator, bobWallet, aliceWallet, alice, 30.0)

      eventually() {
        bobValidatorWallet.listAppRewardCoupons() should have size 1
        bobValidatorWallet.listValidatorRewardCoupons() should have size 1
        aliceValidatorWallet.listAppRewardCoupons() should have size 1
        aliceValidatorWallet.listValidatorRewardCoupons() should have size 1
      }

      val prevBalance = bobValidatorWallet.balance().unlockedQty

      // Bob's validator collects rewards
      // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
      advanceRoundsByOneTick
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      eventually()(bobValidatorWallet.listAppRewardCoupons() should have size 0)
      eventually()(bobValidatorWallet.listValidatorRewardCoupons() should have size 0)

      val newBalance = bobValidatorWallet.balance().unlockedQty

      // We just check that the balance has increased by roughly the right amount,
      // rather then repeating the calculation for the reward amount
      assertInRange(newBalance - prevBalance, (0.1, 0.5))
    }
  }
}
