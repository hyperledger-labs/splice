package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletNewFrontendTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration

class WalletNewTransactionHistoryTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "bob")
    with WalletTestUtil
    with WalletTxLogTestUtil
    with WalletNewFrontendTestUtil
    with FrontendLoginUtil {

  private val coinPrice = 2

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withCoinPrice(coinPrice)

  "A wallet transaction history UI" should {

    val aliceWalletNewPort = 3007

    "show all subscription payments" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceEntryName = perTestCaseName("alice.cns")

      createDirectoryEntryForDirectoryItself

      withFrontEnd("alice") { implicit webDriver =>
        createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)
        val (_, txsBefore) = actAndCheck(
          "Alice goes to wallet", {
            // alice's directory - also taps 5 CC
            browseToWallet(aliceWalletNewPort, aliceDamlUser)
          },
        )(
          "Alice sees the transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            txs should have size 3
            txs
          },
        )

        matchInitialTransactions(txsBefore)

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

        matchLockUnlockDirectoryPayment(txsAfter.take(2))
        matchInitialTransactions(txsAfter.drop(2))
      }
    }

    def matchInitialTransactions(txs: Seq[Element]) = {
      inside(txs) { case rest :+ balanceChange =>
        matchLockUnlockDirectoryPayment(rest)
        matchTransaction(balanceChange)(
          coinPrice = 2,
          expectedAction = "Balance Change",
          expectedParty = None,
          expectedAmountCC = BigDecimal(5),
        )
      }
    }

    def matchLockUnlockDirectoryPayment(txs: Seq[Element]) = {
      inside(txs) { case directoryCreation +: lockForDirectory +: Nil =>
        // Note: this transfer has no effect on the balance of the sender:
        // the input for the app payment is a locked coin that was unlocked in the same transaction.
        matchTransaction(directoryCreation)(
          coinPrice = 2,
          expectedAction = "Sent",
          expectedParty = None,
          expectedAmountCC = BigDecimal(0), // 0 USD
        )
        matchTransaction(lockForDirectory)(
          coinPrice = 2,
          expectedAction = "Automation",
          expectedParty = None,
          expectedAmountCC = BigDecimal("-0.5"), // 1 USD
        )
      }
    }

  }

}
