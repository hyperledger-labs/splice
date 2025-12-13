package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.automation.ReceiveFaucetCouponTrigger

// Split out from WalletTimeBasedIntegrationTest due to test-isolation woes making the test in here flaky.
class WalletAppRewardsTimeBasedIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      // prevent ReceiveFaucetCouponTrigger from seeing stale caches
      .withScanDisabledMiningRoundsCache()
      // TODO (#965) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)

  // TODO (#965) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  "A wallet" should {

    // TODO(DACH-NY/cn-test-failures#5438) Reenable once the Canton issue is fixed
    "handles rewards correctly in the context of 3rd party apps" ignore { implicit env =>
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

        val (_, (aliceAppCoupons, _, aliceValidatorCoupons)) = actAndCheck(
          "Advance rounds until reward coupons are issued",
          Seq(0, 1).foreach(_ => {
            eventually() {
              val currentRound =
                sv1ScanBackend.getOpenAndIssuingMiningRounds()._1.head.contract.payload.round.number
              aliceValidatorWalletClient
                .listValidatorLivenessActivityRecords()
                .map(_.payload.round.number) should contain(currentRound)
            }
            advanceRoundsToNextRoundOpening
          }),
        )(
          "Wait for all reward coupons",
          _ => {
            // App reward coupon to alice's validator for the first (locking) leg
            val aliceAppCoupons =
              aliceValidatorWalletClient.listAppRewardCoupons()
            aliceAppCoupons should have length 1
            // App reward to splitwell provider for the second leg
            val splitwellAppCoupons =
              splitwellWalletClient.listAppRewardCoupons()
            splitwellAppCoupons should have length 1
            // One validator reward coupon per leg to alice's validator
            val aliceValidatorCoupons =
              aliceValidatorWalletClient.listValidatorRewardCoupons()
            aliceValidatorCoupons should have length 2
            // Validator faucet coupons are checked as part of the advance rounds loop above,
            // because by this point they might be claimed already.
            (aliceAppCoupons, splitwellAppCoupons, aliceValidatorCoupons)
          },
        )

        aliceValidatorBackend.validatorAutomation
          .trigger[ReceiveFaucetCouponTrigger]
          .pause()
          .futureValue

        val feeCeiling = walletUsdToAmulet(smallAmount)

        actAndCheck(
          "Advance rounds again to collect rewards",
          Seq(2, 3).foreach(_ => advanceRoundsToNextRoundOpening),
        )(
          "Earn rewards",
          _ => {
            aliceValidatorWalletClient.listAppRewardCoupons() should be(empty)
            splitwellWalletClient.listAppRewardCoupons() should be(empty)
            aliceValidatorWalletClient.listValidatorRewardCoupons() should be(empty)
            aliceValidatorWalletClient
              .listValidatorLivenessActivityRecords()
              .filter(_.payload.round.number < 2) should be(empty)
            logger.info(
              s"Unlocked: ${aliceValidatorStartBalance.unlockedQty}; apps: ${aliceAppCoupons
                  .map(_.payload.amount)
                  .map(BigDecimal(_))
                  .sum}; validator: ${aliceValidatorCoupons
                  .map(_.payload.amount)
                  .map(BigDecimal(_))
                  .sum}"
            )
            val expectedBalance = aliceValidatorStartBalance.unlockedQty + aliceAppCoupons
              .map(_.payload.amount)
              .map(BigDecimal(_))
              .sum + aliceValidatorCoupons
              .map(_.payload.amount)
              .map(BigDecimal(_))
              .sum + walletUsdToAmulet(2.85) * 3 // 2.85 USD per faucet coupon
            checkBalance(
              aliceValidatorWalletClient,
              Some(aliceValidatorStartBalance.round + 4),
              (
                expectedBalance - feeCeiling,
                expectedBalance + walletUsdToAmulet(
                  2.85
                ), // the last validator faucet may or may not have been received/claimed
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

        aliceValidatorBackend.validatorAutomation
          .trigger[ReceiveFaucetCouponTrigger]
          .resume()
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
