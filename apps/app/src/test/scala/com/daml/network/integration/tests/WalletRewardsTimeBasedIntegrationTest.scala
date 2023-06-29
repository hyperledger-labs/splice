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
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )

  "A wallet" should {

    "list and automatically collect app & validator rewards" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)

      // Tap coin and do a transfer from alice to bob
      aliceWalletClient.tap(50)

      p2pTransfer(aliceValidatorBackend, aliceWalletClient, bobWalletClient, bob, 40.0)

      // Retrieve transferred coin in bob's wallet and transfer part of it back to alice;
      // bob's validator will receive some app rewards
      eventually()(bobWalletClient.list().coins should have size 1)
      p2pTransfer(bobValidatorBackend, bobWalletClient, aliceWalletClient, alice, 30.0)

      eventually() {
        bobValidatorWalletClient.listAppRewardCoupons() should have size 1
        bobValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        aliceValidatorWalletClient.listAppRewardCoupons() should have size 1
        aliceValidatorWalletClient.listValidatorRewardCoupons() should have size 1
      }

      val prevBalance = bobValidatorWalletClient.balance().unlockedQty

      // Bob's validator collects rewards
      // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
      advanceRoundsByOneTick
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      eventually()(bobValidatorWalletClient.listAppRewardCoupons() should have size 0)
      eventually()(bobValidatorWalletClient.listValidatorRewardCoupons() should have size 0)

      val newBalance = bobValidatorWalletClient.balance().unlockedQty

      // We just check that the balance has increased by roughly the right amount,
      // rather then repeating the calculation for the reward amount
      assertInRange(newBalance - prevBalance, (0.1, 0.5))
    }
  }
}
