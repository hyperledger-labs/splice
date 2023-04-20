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

      // Transfer from Bob to Alice. Bob's validator will receive some rewards
      p2pTransfer(bobValidator, bobWallet, aliceWallet, alice, 30.0)

      eventually() {
        bobValidatorWallet.listAppRewardCoupons() should have size 1
        bobValidatorWallet.listValidatorRewardCoupons() should have size 1
      }

      /*val balanceBefore = */
      bobValidatorWallet.balance().unlockedQty

      // Bob's validator collects rewards
      // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
      advanceRoundsByOneTick
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      eventually()(bobValidatorWallet.listAppRewardCoupons() should have size 0)
      eventually()(bobValidatorWallet.listValidatorRewardCoupons() should have size 0)

      /*val balanceAfter = */
      bobValidatorWallet.balance().unlockedQty

      checkTxHistory(
        bobValidatorWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: walletLogEntry.Transfer =>
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.WalletAutomation
          // TODO(#4191) this entry should show the net change in the balance, which it currently does not
          /* inside(logEntry.receivers) { case Seq(receiver, amount) =>
              receiver shouldBe bobValidator.config.ledgerApiUser
              amount should be (balanceAfter - balanceBefore)
            } */
          }
        ),
      )
    }
  }

}
