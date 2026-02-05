package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  SvTestUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
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

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .withAmuletPrice(amuletPrice)

  "A wallet transaction history UI" should {

    "show all subscription payments" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceEntryName = perTestCaseName("alice")

      val entryForAns = expectedDsoAns

      withFrontEnd("alice") { implicit webDriver =>
        createAnsEntry(
          aliceAnsExternalClient,
          aliceEntryName,
          aliceWalletClient,
          tapAmount = 5.0 * amuletPrice,
        )
        val (_, txsBefore) = actAndCheck(
          "Alice goes to wallet", {
            // alice's directory - also taps 5 Amulet
            browseToAliceWallet(aliceDamlUser)
          },
        )(
          "Alice sees the transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            txs should have size 3
            forAll(txs.take(2))(readPartyDescriptionFromRow(_) shouldBe defined)
            txs
          },
        )

        matchInitialTransactions(txsBefore, entryForAns)

        val (_, txsAfter) = actAndCheck(
          "time passes for the next payment to happen", {
            // Advance so we're within the renewalInterval + make sure that we have
            // an open round that we can use. We time the advances so that
            // automation doesn't trigger before payments can be made.
            // TODO (#996): consider replacing with stopping and starting triggers
            advanceTimeAndWaitForRoundAutomation(Duration.ofDays(89).minus(Duration.ofMinutes(1)))
            advanceTimeToRoundOpen
          },
        )(
          "Alice sees the new transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            txs should have size 5
            forAll(txs.take(2))(readPartyDescriptionFromRow(_) shouldBe defined)
            txs
          },
        )

        matchLockUnlockAnsPayment(txsAfter.take(2), entryForAns, isInitial = false)
        matchInitialTransactions(txsAfter.drop(2), entryForAns)
      }
    }

    def matchInitialTransactions(txs: Seq[Element], entryForAns: String)(implicit
        env: SpliceTestConsoleEnvironment
    ) = {
      inside(txs) { case rest :+ balanceChange =>
        matchLockUnlockAnsPayment(rest, entryForAns, isInitial = true)
        matchTransaction(balanceChange)(
          amuletPrice = 2,
          expectedAction = "Balance Change",
          expectedSubtype = "Tap",
          expectedPartyDescription = None,
          expectedAmountAmulet = BigDecimal(5),
        )
      }
    }

    def matchLockUnlockAnsPayment(
        txs: Seq[Element],
        entryForAns: String,
        isInitial: Boolean,
    )(implicit
        env: SpliceTestConsoleEnvironment
    ) = {
      inside(txs) { case ansCreation +: lockForAns +: Nil =>
        // Note: this transfer has no effect on the balance of the sender:
        // the input for the app payment is a locked amulet that was unlocked in the same transaction.
        matchTransaction(ansCreation)(
          amuletPrice = 2,
          expectedAction = "Sent",
          expectedSubtype =
            if (isInitial)
              s"${ansAcronym.toUpperCase()} Entry Initial Payment Collected"
            else s"${ansAcronym.toUpperCase()} Entry Renewal Payment Collected",
          expectedPartyDescription = Some(s"$entryForAns"),
          expectedAmountAmulet = BigDecimal(0), // 0 USD
        )
        matchTransaction(lockForAns)(
          amuletPrice = 2,
          expectedAction = "Sent",
          expectedSubtype =
            if (isInitial)
              "Subscription Initial Payment Accepted"
            else "Subscription Payment Accepted",
          expectedPartyDescription = Some(s"Automation"),
          expectedAmountAmulet = BigDecimal("-0.5"), // 1 USD
        )
      }
    }

  }

}
