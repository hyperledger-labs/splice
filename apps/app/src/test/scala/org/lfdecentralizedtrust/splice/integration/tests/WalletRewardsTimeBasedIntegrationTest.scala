package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SpliceUtil, TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  ValidatorRewardCoupon,
}

import scala.concurrent.duration.DurationInt

class WalletRewardsTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // TODO (#965) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)

  // TODO (#965) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  override protected lazy val sanityChecksIgnoredRootCreates = Seq(
    AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
    ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  "A wallet" should {

    "list and automatically collect app & validator rewards" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      val bobValidatorParty = bobValidatorBackend.getValidatorPartyId()

      // Tap amulet and do a transfer from alice to bob
      aliceWalletClient.tap(walletAmuletToUsd(50))

      p2pTransfer(aliceWalletClient, bobWalletClient, bob, 40.0)
      // Rewards roughly match what we had before we set fees to zero
      createRewards(
        appRewards = Seq((aliceValidatorParty, 0.43, false)),
        validatorRewards = Seq((alice, 0.43)),
      )

      // Retrieve transferred amulet in bob's wallet and transfer part of it back to alice;
      // bob's validator will receive some app rewards
      eventually()(bobWalletClient.list().amulets should have size 1)
      p2pTransfer(bobWalletClient, aliceWalletClient, alice, 30.0)
      // Rewards roughly match what we had before we set fees to zero
      createRewards(
        appRewards = Seq((bobValidatorParty, 0.33, false)),
        validatorRewards = Seq((bob, 0.33)),
      )

      val openRounds = eventually() {
        import math.Ordering.Implicits.*
        val openRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
        openRounds should not be empty
        openRounds
      }

      advanceTimeForRewardAutomationToRunForCurrentRound

      eventually(40.seconds) {
        bobValidatorWalletClient.listAppRewardCoupons() should have size 1
        bobValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        aliceValidatorWalletClient.listAppRewardCoupons() should have size 1
        aliceValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        bobValidatorWalletClient
          .listValidatorLivenessActivityRecords() should have size openRounds.size.toLong
        aliceValidatorWalletClient
          .listValidatorLivenessActivityRecords() should have size openRounds.size.toLong
      }

      // avoid messing with the computation of balance
      bobValidatorBackend.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
        .pause()
        .futureValue

      val prevBalance = bobValidatorWalletClient.balance().unlockedQty

      // Bob's validator collects rewards
      // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      advanceTimeForRewardAutomationToRunForCurrentRound

      eventually() {
        bobValidatorWalletClient.listAppRewardCoupons() should have size 0
        bobValidatorWalletClient.listValidatorRewardCoupons() should have size 0
        bobValidatorWalletClient.listValidatorLivenessActivityRecords() should have size 0

        val newBalance = bobValidatorWalletClient.balance().unlockedQty

        // We just check that the balance has increased by roughly the right amount,
        // rather then repeating the calculation for the reward amount
        // 2.85 USD per faucet coupon
        val faucetCouponAmountUsd = 2.85 * openRounds.size
        assertInRange(
          newBalance - prevBalance,
          (
            walletUsdToAmulet(-0.1 + faucetCouponAmountUsd),
            walletUsdToAmulet(0.5 + faucetCouponAmountUsd),
          ),
        )
      }
    }
  }
}
