package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment.Currency
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletNewFrontendTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletNewTransactionHistoryIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "bob")
    with WalletTestUtil
    with WalletTxLogTestUtil
    with WalletNewFrontendTestUtil
    with FrontendLoginUtil {

  private val coinPrice = 2

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withCoinPrice(coinPrice)

  "A wallet transaction history UI" should {

    "show all types of transactions" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceEntryName = perTestCaseName("alice.cns")
      waitForWalletUser(aliceValidatorWallet)
      val aliceValidatorParty = aliceValidatorWallet.userStatus().party

      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
      val charlieEntryName = perTestCaseName("charlie.cns")
      createDirectoryEntry(charlieUserParty, charlieDirectory, charlieEntryName, charlieWallet)

      val directoryExpectedCns = createDirectoryEntryForDirectoryItself

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice goes to her wallet", {
            browseToAliceWallet(aliceDamlUser)
          },
        )(
          "Alice sees no transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            txs should have size 0
          },
        )

        val (_, txs) = actAndCheck(
          "Transactions are done", {
            // alice's directory - also taps 5 CC
            createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)
            // charlie -> alice
            charlieWallet.tap(50)
            p2pTransfer(
              aliceValidator,
              charlieWallet,
              aliceWallet,
              aliceUserParty,
              BigDecimal("1.07"),
            )
            // alice -> charlie
            p2pTransfer(
              aliceValidator,
              aliceWallet,
              charlieWallet,
              charlieUserParty,
              BigDecimal("1.18"),
            )
            // one-time payment
            val (_, cid, _) = createPaymentRequest(
              aliceValidator.remoteParticipantWithAdminToken,
              aliceDamlUser,
              aliceUserParty,
              receiverAmounts = Seq(
                receiverAmount(charlieUserParty, BigDecimal("1.31415"), Currency.CC)
              ),
            )
            eventuallySucceeds() {
              aliceWallet.acceptAppPaymentRequest(cid)
            }
          },
        )(
          "Alice sees the transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            txs should have size 6
            txs
          },
        )

        inside(txs) {
          case otp +: sent +: received +: directoryCreation +: lockForDirectory +: balanceChange +: Nil =>
            matchTransaction(otp)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedPartyDescription = Some(s"Automation via $aliceValidatorParty"),
              expectedAmountCC = BigDecimal("-1.31415"),
            )
            matchTransaction(sent)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedPartyDescription = Some(
                s"${expectedCns(charlieUserParty, charlieEntryName)} via $aliceValidatorParty"
              ),
              expectedAmountCC = BigDecimal("-1.18"),
            )
            matchTransaction(received)(
              coinPrice = 2,
              expectedAction = "Received",
              expectedPartyDescription = Some(
                s"${expectedCns(charlieUserParty, charlieEntryName)} via $aliceValidatorParty"
              ),
              expectedAmountCC = BigDecimal("1.07"),
            )
            // Note: this transfer has no effect on the balance of the sender:
            // the input for the app payment is a locked coin that was unlocked in the same transaction.
            matchTransaction(directoryCreation)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedPartyDescription = Some(s"$directoryExpectedCns via $directoryExpectedCns"),
              expectedAmountCC = BigDecimal(0), // 0 USD
            )
            matchTransaction(lockForDirectory)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedPartyDescription = Some(s"Automation via $aliceValidatorParty"),
              expectedAmountCC = BigDecimal("-0.5"), // 1 USD
            )
            matchTransaction(balanceChange)(
              coinPrice = 2,
              expectedAction = "Balance Change",
              expectedPartyDescription = None,
              expectedAmountCC = BigDecimal(5),
            )
        }
      }
    }

  }

}
