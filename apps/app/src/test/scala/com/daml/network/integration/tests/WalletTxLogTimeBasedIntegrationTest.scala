package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry as walletLogEntry
import com.digitalasset.canton.HasExecutionContext

class WalletTxLogTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil {

  private val coinPrice = BigDecimal(1.25).setScale(10)

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndCoinMerging
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(coinPrice)(config))
  }

  "A wallet" should {

    "handle sv rewards" in { implicit env =>
      val sv1UserParty = onboardWalletUser(sv1Wallet, sv1Validator)
      logger.info(s"SV1 wallet uses party $sv1UserParty")

      actAndCheck(
        "Advance round",
        advanceRoundsByOneTick,
      )(
        "Wait for SV rewards to be collected",
        _ => {
          advanceTimeByPollingInterval(sv1)
          sv1Wallet.balance().unlockedQty should be > BigDecimal(0)
        },
      )
      checkTxHistory(
        sv1Wallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.SvRewardCollected
            logEntry.amount should be > BigDecimal(0)
          }
        ),
      )
    }

    "handle app and validator rewards" in { implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWallet)
      waitForWalletUser(bobValidatorWallet)

      clue("Tap to get some coins") {
        aliceWallet.tap(100.0)
        aliceValidatorWallet.tap(100.0)
      }

      actAndCheck(
        "Alice transfers some CC to Bob",
        p2pTransfer(aliceValidator, aliceWallet, bobWallet, bobUserParty, 40.0),
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
          aliceValidatorWallet.listAppRewardCoupons() should have size 1
          aliceValidatorWallet.listValidatorRewardCoupons() should have size 1
        }
      }

      actAndCheck(
        "Alice's validator transfers some CC to Bob (using her app & validator rewards)",
        p2pTransfer(aliceValidator, aliceValidatorWallet, bobWallet, bobUserParty, 10.0),
      )(
        "Bob has received the CC",
        _ => {
          bobWallet.balance().unlockedQty should be > BigDecimal(49.0)
        },
      )

      checkTxHistory(
        bobWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: walletLogEntry.Transfer =>
            // Alice's validator sending 10CC to Bob, using their validator&app rewards and their coin
            // TODO(#3525): this transfer should show the rewards used
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.P2PPaymentCompleted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceValidator.getValidatorPartyId().toProtoPrimitive
              amount should beWithin(-10 - smallAmount, BigDecimal(-10))
            }
            inside(logEntry.receivers) { case Seq((receiver, amount)) =>
              receiver shouldBe bobUserParty.toProtoPrimitive
              amount should beWithin(10 - smallAmount, BigDecimal(10))
            }
            logEntry.senderHoldingFees should be > BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.Transfer =>
            // Alice sending 40CC to Bob
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.P2PPaymentCompleted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-40 - smallAmount, -40)
            }
            inside(logEntry.receivers) { case Seq((receiver, amount)) =>
              receiver shouldBe bobUserParty.toProtoPrimitive
              amount should beWithin(40 - smallAmount, 40)
            }
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
        ),
      )
    }

    "handle expired coins in a transaction history" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      actAndCheck(
        "Tap to get a small coin",
        aliceWallet.tap(0.000005),
      )(
        "Wait for coin to appear",
        _ => aliceWallet.list().coins.size shouldBe 1,
      )

      actAndCheck(
        "Advance 4 ticks to expire the coin",
        Range(0, 4).foreach(_ => advanceRoundsByOneTick),
      )(
        "Wait for coin to disappear",
        _ => aliceWallet.list().coins.size shouldBe 0,
      )

      checkTxHistory(
        aliceWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.CoinExpired
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
          },
        ),
      )

    }

    "handle expired locked coins in a transaction history" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWallet, aliceValidator)
      val aliceValidatorParty = aliceValidator.getValidatorPartyId()

      actAndCheck(
        "Tap to get some coin",
        aliceWallet.tap(100),
      )(
        "Wait for coin to appear",
        _ => aliceWallet.list().coins.size shouldBe 1,
      )

      actAndCheck(
        "Lock a small coin",
        lockCoins(
          aliceValidator,
          aliceParty,
          aliceValidatorParty,
          aliceWallet.list().coins,
          BigDecimal(0.000005),
          scan,
          java.time.Duration.ofMinutes(5),
        ),
      )(
        "Wait for locked coin to appear",
        _ => aliceWallet.list().lockedCoins.size shouldBe 1,
      )

      actAndCheck(
        "Advance 4 ticks to expire the locked coin",
        Range(0, 4).foreach(_ => advanceRoundsByOneTick),
      )(
        "Wait for locked coin to disappear",
        _ => aliceWallet.list().lockedCoins.size shouldBe 0,
      )

      checkTxHistory(
        aliceWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.LockedCoinExpired
          },
          { case logEntry: walletLogEntry.Transfer =>
            // The `lockCoins` utility function directly exercises the `CoinRules_Transfer` choice.
            // This will appear as the catch-all "unknown transfer" in the history.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.Transfer
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
          },
        ),
      )

    }
  }

}
