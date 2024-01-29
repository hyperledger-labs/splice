package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.*
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

// Split out from WallteTimeBasedIntegrationTest due to test-isolation woes making the test in here flaky.
class WalletAppRewardsTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })

  "A wallet" should {

    "handles rewards correctly in the context of 3rd party apps" in { implicit env =>
      val (_, bobUserParty, _, splitwellProviderParty, key, _) =
        initSplitwellTest()

      aliceWalletClient.tap(350.0)

      def transferAndCheckRewards(expectedAppRewardsRange: (BigDecimal, BigDecimal)) = {
        clue("Transfer some cc through splitwell") {
          splitwellTransfer(
            aliceSplitwellClient,
            aliceWalletClient,
            bobUserParty,
            BigDecimal(100.0),
            key,
          )
        }

        val aliceValidatorStartBalance = aliceValidatorWalletClient.balance()
        val providerStartBalance = splitwellWalletClient.balance()

        actAndCheck(
          "Advance rounds until reward coupons are issued",
          Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
        )(
          "Wait for all reward coupons",
          _ => {
            // App reward coupon to alice's validator for the first (locking) leg
            aliceValidatorWalletClient.listAppRewardCoupons() should have length 1
            // App reward to splitwell provider for the second leg
            splitwellWalletClient.listAppRewardCoupons() should have length 1
            // One validator reward coupon per leg to alice's validator
            aliceValidatorWalletClient.listValidatorRewardCoupons() should have length 2
            // TODO(#9551): also test for validator faucet coupons
          },
        )

        actAndCheck(
          "Advance rounds again to get rewards",
          Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
        )(
          "Earn rewards",
          _ => {
            aliceValidatorWalletClient.listAppRewardCoupons() should be(empty)
            splitwellWalletClient.listAppRewardCoupons() should be(empty)
            aliceValidatorWalletClient.listValidatorRewardCoupons() should be(empty)
            // TODO(#9551): also test for validator faucet coupons
            checkBalance(
              aliceValidatorWalletClient,
              Some(aliceValidatorStartBalance.round + 4),
              (
                aliceValidatorStartBalance.unlockedQty,
                aliceValidatorStartBalance.unlockedQty + 5.0,
              ),
              (0, 0),
              (0, 1),
            )
            checkBalance(
              splitwellWalletClient,
              Some(providerStartBalance.round + 4),
              (
                providerStartBalance.unlockedQty + expectedAppRewardsRange._1,
                providerStartBalance.unlockedQty + expectedAppRewardsRange._2,
              ),
              (0, 0),
              (0, 1),
            )
          },
        )
      }

      transferAndCheckRewards((202.9, 203))

      actAndCheck(
        "Splitwell cancels its own featured app right",
        splitwellWalletClient.cancelFeaturedAppRight(),
      )(
        "Splitwell is no longer featured",
        _ => sv1ScanBackend.lookupFeaturedAppRight(splitwellProviderParty) should be(None),
      )

      transferAndCheckRewards((0.5, 0.6))
    }

  }

}
