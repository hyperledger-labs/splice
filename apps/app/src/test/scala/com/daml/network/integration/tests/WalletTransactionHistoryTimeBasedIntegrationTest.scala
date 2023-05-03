package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.UserWalletTxLogParser
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.OptionValues

import java.time.Duration

class WalletTransactionHistoryTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "bob", "sv1")
    with WalletTestUtil
    with WalletTxLogTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with OptionValues {

  private val coinPrice = 2

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withCoinPrice(coinPrice)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)

  "A wallet transaction history UI" should {

    "show all subscription payments" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceEntryName = perTestCaseName("alice.cns")

      val directoryExpectedCns = createDirectoryEntryForDirectoryItself

      withFrontEnd("alice") { implicit webDriver =>
        createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)
        val (_, txsBefore) = actAndCheck(
          "Alice goes to wallet", {
            // alice's directory - also taps 5 CC
            browseToAliceWallet(aliceDamlUser)
          },
        )(
          "Alice sees the transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            txs should have size 3
            txs
          },
        )

        matchInitialTransactions(txsBefore, directoryExpectedCns)

        val (_, txsAfter) = actAndCheck(
          "time passes for the next payment to happen", {
            // Advance so we're within the renewalInterval + make sure that we have
            // an open round that we can use. We time the advances so that
            // automation doesn't trigger before payments can be made.
            advanceTimeAndWaitForRoundAutomation(Duration.ofDays(89).minus(Duration.ofMinutes(1)))
            advanceTimeToRoundOpen
          },
        )(
          "Alice sees the new transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            txs should have size 5
            txs
          },
        )

        matchLockUnlockDirectoryPayment(txsAfter.take(2), directoryExpectedCns, isInitial = false)
        matchInitialTransactions(txsAfter.drop(2), directoryExpectedCns)
      }
    }

    "show sv rewards collection in tx history" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        val sv1WalletUser = sv1Validator.config.validatorWalletUser.value
        browseToSv1Wallet(sv1WalletUser)
        actAndCheck(
          "Advance round",
          advanceRoundsByOneTick,
        )(
          "Wait for SV rewards to be collected and show up in the tx history",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            // We can get more than one entry, e.g., coin merging can also kick in
            // so we only match on at least one entry being the reward collection.
            forAtLeast(1, txs) { tx =>
              matchTransactionAmountRange(tx)(
                coinPrice = 2,
                expectedAction = "Balance Change",
                expectedSubtype = UserWalletTxLogParser.TxLogEntry.BalanceChange.SvRewardCollected,
                expectedPartyDescription = None,
                // Rewards depend on round activity which can fluctuate a bit due to automation so we accept a wide range.
                expectedAmountCC = (BigDecimal(66500), BigDecimal(66600)),
                expectedAmountUSD = (BigDecimal(133000), BigDecimal(133200)),
              )
            }
          },
        )
      }
    }

    def matchInitialTransactions(txs: Seq[Element], directoryExpectedCns: String)(implicit
        env: CNNodeTestConsoleEnvironment
    ) = {
      inside(txs) { case rest :+ balanceChange =>
        matchLockUnlockDirectoryPayment(rest, directoryExpectedCns, isInitial = true)
        matchTransaction(balanceChange)(
          coinPrice = 2,
          expectedAction = "Balance Change",
          expectedSubtype = UserWalletTxLogParser.TxLogEntry.BalanceChange.Tap,
          expectedPartyDescription = None,
          expectedAmountCC = BigDecimal(5),
        )
      }
    }

    def matchLockUnlockDirectoryPayment(
        txs: Seq[Element],
        directoryExpectedCns: String,
        isInitial: Boolean,
    )(implicit
        env: CNNodeTestConsoleEnvironment
    ) = {
      inside(txs) { case directoryCreation +: lockForDirectory +: Nil =>
        // Note: this transfer has no effect on the balance of the sender:
        // the input for the app payment is a locked coin that was unlocked in the same transaction.
        matchTransaction(directoryCreation)(
          coinPrice = 2,
          expectedAction = "Sent",
          expectedSubtype =
            if (isInitial)
              UserWalletTxLogParser.TxLogEntry.Transfer.SubscriptionInitialPaymentCollected
            else UserWalletTxLogParser.TxLogEntry.Transfer.SubscriptionPaymentCollected,
          expectedPartyDescription = Some(s"$directoryExpectedCns via $directoryExpectedCns"),
          expectedAmountCC = BigDecimal(0), // 0 USD
        )
        matchTransaction(lockForDirectory)(
          coinPrice = 2,
          expectedAction = "Sent",
          expectedSubtype =
            if (isInitial)
              UserWalletTxLogParser.TxLogEntry.Transfer.SubscriptionInitialPaymentAccepted
            else UserWalletTxLogParser.TxLogEntry.Transfer.SubscriptionPaymentAccepted,
          expectedPartyDescription =
            Some(s"Automation via ${aliceValidator.getValidatorPartyId().toProtoPrimitive}"),
          expectedAmountCC = BigDecimal("-0.5"), // 1 USD
        )
      }
    }

  }

}
