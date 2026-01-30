package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.{SplitwellTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  TransferTxLogEntry,
  TxLogEntry as walletLogEntry,
}
import com.digitalasset.canton.HasExecutionContext

import java.util.UUID

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
      val (alice, bob) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)

      // Self-feature to get app rewards
      grantFeaturedAppRight(bobValidatorWalletClient)
      // Tap to pay preapproval fees
      bobValidatorWalletClient.tap(100.0)
      bobWalletClient.createTransferPreapproval()

      aliceWalletClient.tap(60.0)

      actAndCheck(
        "Transfer from Alice to Bob",
        aliceWalletClient.transferPreapprovalSend(bob, 30.0, UUID.randomUUID.toString),
      )(
        "Bob's validator will receive some rewards",
        _ => {
          // from the featured incoming transfer
          bobValidatorWalletClient.listAppRewardCoupons() should have size 1
          // from creating the transfer preapproval
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
        getRewardCouponsValue(appRewards, validatorRewards)

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
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.TransferPreapprovalCreation.toProto
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
          },
        ),
        trafficTopups = IgnoreTopupsDevNet,
      )
    }
  }

}
