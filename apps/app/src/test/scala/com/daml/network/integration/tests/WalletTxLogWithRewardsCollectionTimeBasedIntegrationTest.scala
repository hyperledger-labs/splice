package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry as walletLogEntry
import com.digitalasset.canton.HasExecutionContext

class WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil {

  private val coinPrice = BigDecimal(1.25).setScale(10)

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(coinPrice)(config))
  }

  "A wallet" should {

    "handle app and validator rewards that are collected" in { implicit env =>
      val (alice, _) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWallet)
      waitForWalletUser(bobValidatorWallet)

      grantFeaturedAppRight(bobValidatorWallet)

      bobWallet.tap(50)

      actAndCheck(
        "Transfer from Bob to Alice",
        p2pTransfer(bobValidator, bobWallet, aliceWallet, alice, 30.0),
      )(
        "Bob's validator will receive some rewards",
        _ => {
          bobValidatorWallet.listAppRewardCoupons() should have size 1
          bobValidatorWallet.listValidatorRewardCoupons() should have size 1
        },
      )

      val appRewards = bobValidatorWallet.listAppRewardCoupons()
      val validatorRewards = bobValidatorWallet.listValidatorRewardCoupons()

      val balanceBefore = bobValidatorWallet.balance().unlockedQty
      val (_, balanceAfter) = actAndCheck(
        "It takes 3 ticks for the IssuingMiningRound 1 to be created and open.", {
          advanceRoundsByOneTick
          advanceRoundsByOneTick
          advanceRoundsByOneTick
        },
      )(
        "Bob's validator collects rewards",
        _ => {
          bobValidatorWallet.listAppRewardCoupons() should have size 0
          bobValidatorWallet.listValidatorRewardCoupons() should have size 0
          val balanceAfter = bobValidatorWallet.balance().unlockedQty
          balanceAfter should be > balanceBefore
          balanceAfter
        },
      )

      val (appRewardAmount, validatorRewardAmount) =
        getRewardCouponsValue(appRewards, validatorRewards, true)

      checkTxHistory(
        bobValidatorWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: walletLogEntry.Transfer =>
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.WalletAutomation
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe bobValidator.getValidatorPartyId().toProtoPrimitive
              amount should be(balanceAfter - balanceBefore)
            }
            logEntry.receivers shouldBe empty
            logEntry.appRewardsUsed shouldBe appRewardAmount
            logEntry.validatorRewardsUsed shouldBe validatorRewardAmount
          }
        ),
      )
    }
  }

}
