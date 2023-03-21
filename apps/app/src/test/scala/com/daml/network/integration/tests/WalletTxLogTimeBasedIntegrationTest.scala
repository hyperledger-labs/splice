package com.daml.network.integration.tests

import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTestWithSharedEnvironment
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.UserWalletTxLogParser
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

class WalletTxLogTimeBasedIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: CoinEnvironmentDefinition = {
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndCoinMerging
  }

  "A wallet" should {

    "handle rewards" in { implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWallet)
      waitForWalletUser(bobValidatorWallet)

      clue("Tap to get some coins") {
        aliceWallet.tap(100.0)
        aliceValidatorWallet.tap(100.0)
      }

      actAndCheck(
        "Alice transfers some CC to Bob",
        p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bobUserParty, 40.0),
      )(
        "Bob has received the CC",
        _ => bobWallet.balance().unlockedQty should be > BigDecimal(39.0),
      )

      // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
      clue("Advance rounds by 3 ticks.") {
        advanceRoundsByOneTick
        advanceRoundsByOneTick
        advanceRoundsByOneTick
      }

      clue("Everyone still has their reward coupons") {
        eventually() {
          aliceWallet.listAppRewardCoupons() should have size 1
          aliceValidatorWallet.listValidatorRewardCoupons() should have size 1
        }
      }

      actAndCheck(
        "Alice transfers some CC to Bob (using her app reward)",
        p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bobUserParty, 10.0),
      )(
        "Bob has received the CC and the app reward coupon is gone",
        _ => {
          bobWallet.balance().unlockedQty should be > BigDecimal(49.0)
          // The payment that consumed the app reward coupon has created a new app reward coupon,
          // here we check that the remaining coupon is the new one.
          inside(aliceWallet.listAppRewardCoupons()) { case Seq(coupon) =>
            coupon.payload.round.number shouldBe 4
          }
        },
      )

      actAndCheck(
        "Alice's validator transfers some CC to Bob (using their validator reward)",
        p2pTransferAndTriggerAutomation(aliceValidatorWallet, bobWallet, bobUserParty, 10.0),
      )(
        "Bob has received the CC and the validator reward coupon is gone",
        _ => {
          bobWallet.balance().unlockedQty should be > BigDecimal(59.0)
          // The validator reward coupon from round 1 was consumed for the above payment,
          // but the two payments in round 4 have created two new reward coupons.
          inside(aliceValidatorWallet.listValidatorRewardCoupons()) { case Seq(coupon1, coupon2) =>
            coupon1.payload.round.number shouldBe 4
            coupon2.payload.round.number shouldBe 4
          }
        },
      )

      checkTxHistory(
        bobWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Alice sending 40CC to Bob
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(40, 40 + smallAmount)
            }
            inside(logEntry.receivers) { case Seq((receiver, amount)) =>
              receiver shouldBe bobUserParty.toProtoPrimitive
              amount should beWithin(40 - smallAmount, 40)
            }
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Alice sending 10CC to Bob, using her app reward and her coin
            // TODO(#3525): this transfer should show the app rewards used
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(10, 10 + smallAmount)
            }
            inside(logEntry.receivers) { case Seq((receiver, amount)) =>
              receiver shouldBe bobUserParty.toProtoPrimitive
              amount should beWithin(10 - smallAmount, 10)
            }
            logEntry.senderHoldingFees should be > BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Alice's validator sending 10CC to Bob, using their validator reward and their coin
            // TODO(#3525): this transfer should show the validator rewards used
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceValidator.getValidatorPartyId().toProtoPrimitive
              amount should beWithin(BigDecimal(10), 10 + smallAmount)
            }
            inside(logEntry.receivers) { case Seq((receiver, amount)) =>
              receiver shouldBe bobUserParty.toProtoPrimitive
              amount should beWithin(BigDecimal(10) - smallAmount, BigDecimal(10))
            }
            logEntry.senderHoldingFees should be > BigDecimal(0)
          },
        ),
      )
    }

    "handle expired coins in a transaction history" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      actAndCheck("Tap to get some coins", aliceWallet.tap(0.000005))(
        "Wait for tap to appear in history",
        _ =>
          aliceWallet.listTransactions(None, 5) should matchPattern {
            case Seq(_: UserWalletTxLogParser.TxLogEntry.BalanceChange) =>
          },
      )

      aliceWallet.list().coins should have size 1

      // advance 4 ticks to expire the coin
      // TODO (#2845) Adapt this once we properly handle expired coins.
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        {
          Range(0, 4).foreach(_ => advanceRoundsByOneTick)

          eventually() {
            aliceWallet.list().coins should have size 0
          }
        },
        entries =>
          forAtLeast(1, entries)(
            _.message should include(
              "Coin archive events are not included in the transaction history."
            )
          ),
      )
    }
  }

}
