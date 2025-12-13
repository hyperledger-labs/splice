package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.{SplitwellTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.store.{
  TransferTxLogEntry,
  TxLogEntry as walletLogEntry,
}
import com.digitalasset.canton.HasExecutionContext

class WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil {

  private val amuletPrice = BigDecimal(1.25).setScale(10)

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // Set a non-unit amulet price to better test CC-USD conversion.
      .addConfigTransform((_, config) => ConfigTransforms.setAmuletPrice(amuletPrice)(config))
      .addConfigTransforms((_, config) =>
        // without this, you can have 1 or 2 transfers in the txlog, or just 1 with different balance
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
  }

  "A wallet" should {

    "handle app and validator rewards that are collected" in { implicit env =>
      val (alice, _) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)

      // Note this has no effect on the wallet app, as it is not a featured app and thus does not use the featured app
      // right in the transfer contexts of its submissions. We leave it here to test that it has no effect.
      grantFeaturedAppRight(bobValidatorWalletClient)

      bobWalletClient.tap(50)

      actAndCheck(
        "Transfer from Bob to Alice",
        p2pTransfer(bobWalletClient, aliceWalletClient, alice, 30.0),
      )(
        "Bob's validator will receive some rewards",
        _ => {
          bobValidatorWalletClient.listAppRewardCoupons() should have size 1
          bobValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        },
      )

      val appRewards = bobValidatorWalletClient.listAppRewardCoupons()
      val validatorRewards = bobValidatorWalletClient.listValidatorRewardCoupons()

      val balanceBefore = bobValidatorWalletClient.balance().unlockedQty
      val (_, balanceAfter) = actAndCheck(
        "It takes 3 ticks for the IssuingMiningRound 1 to be created and open.", {
          advanceRoundsToNextRoundOpening
          advanceRoundsToNextRoundOpening
          advanceRoundsToNextRoundOpening
        },
      )(
        "Bob's validator collects rewards",
        _ => {
          bobValidatorWalletClient.listAppRewardCoupons() should have size 0
          bobValidatorWalletClient.listValidatorRewardCoupons() should have size 0
          val balanceAfter = bobValidatorWalletClient.balance().unlockedQty
          balanceAfter should be > balanceBefore
          balanceAfter
        },
      )

      val (appRewardAmount, validatorRewardAmount) =
        getRewardCouponsValue(appRewards, validatorRewards, featured = false)

      checkTxHistory(
        bobValidatorWalletClient,
        Seq[CheckTxHistoryFn](
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.WalletAutomation.toProto
            logEntry.sender.value.party shouldBe bobValidatorBackend
              .getValidatorPartyId()
              .toProtoPrimitive
            logEntry.sender.value.amount should be(balanceAfter - balanceBefore)
            logEntry.receivers shouldBe empty
            logEntry.appRewardsUsed shouldBe appRewardAmount
            logEntry.validatorRewardsUsed shouldBe validatorRewardAmount
          }
        ),
        trafficTopups = IgnoreTopupsDevNet,
      )
    }
  }

}
