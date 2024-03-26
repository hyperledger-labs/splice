package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, SvTestUtil, WalletFrontendTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.OptionValues

import java.time.Duration

class WalletTransactionHistoryFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "sv1")
    with WalletTestUtil
    with WalletTxLogTestUtil
    with WalletFrontendTestUtil
    with SvTestUtil
    with FrontendLoginUtil
    with OptionValues {

  private val amuletPrice = 2

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .withAmuletPrice(amuletPrice)

  "A wallet transaction history UI" should {

    "show all subscription payments" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceEntryName = perTestCaseName("alice")

      val entryForCns = expectedSvcCns

      withFrontEnd("alice") { implicit webDriver =>
        createCnsEntry(
          aliceCnsExternalClient,
          aliceEntryName,
          aliceWalletClient,
          tapAmount = 5.0 * amuletPrice,
        )
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

        matchInitialTransactions(txsBefore, entryForCns)

        val (_, txsAfter) = actAndCheck(
          "time passes for the next payment to happen", {
            // Advance so we're within the renewalInterval + make sure that we have
            // an open round that we can use. We time the advances so that
            // automation doesn't trigger before payments can be made.
            // TODO (#7609): consider replacing with stopping and starting triggers
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

        matchLockUnlockCnsPayment(txsAfter.take(2), entryForCns, isInitial = false)
        matchInitialTransactions(txsAfter.drop(2), entryForCns)
      }
    }

    def matchInitialTransactions(txs: Seq[Element], entryForCns: String)(implicit
        env: CNNodeTestConsoleEnvironment
    ) = {
      inside(txs) { case rest :+ balanceChange =>
        matchLockUnlockCnsPayment(rest, entryForCns, isInitial = true)
        matchTransaction(balanceChange)(
          amuletPrice = 2,
          expectedAction = "Balance Change",
          expectedSubtype = "Tap",
          expectedPartyDescription = None,
          expectedAmountCC = BigDecimal(5),
        )
      }
    }

    def matchLockUnlockCnsPayment(
        txs: Seq[Element],
        entryForCns: String,
        isInitial: Boolean,
    )(implicit
        env: CNNodeTestConsoleEnvironment
    ) = {
      inside(txs) { case cnsCreation +: lockForCns +: Nil =>
        // Note: this transfer has no effect on the balance of the sender:
        // the input for the app payment is a locked amulet that was unlocked in the same transaction.
        matchTransaction(cnsCreation)(
          amuletPrice = 2,
          expectedAction = "Sent",
          expectedSubtype =
            if (isInitial)
              "CNS Entry Initial Payment Collected"
            else "CNS Entry Renewal Payment Collected",
          expectedPartyDescription = Some(s"$entryForCns $entryForCns"),
          expectedAmountCC = BigDecimal(0), // 0 USD
        )
        matchTransaction(lockForCns)(
          amuletPrice = 2,
          expectedAction = "Sent",
          expectedSubtype =
            if (isInitial)
              "Subscription Initial Payment Accepted"
            else "Subscription Payment Accepted",
          expectedPartyDescription =
            Some(s"Automation ${aliceValidatorBackend.getValidatorPartyId().toProtoPrimitive}"),
          expectedAmountCC = BigDecimal("-0.5"), // 1 USD
        )
      }
    }

  }

}
