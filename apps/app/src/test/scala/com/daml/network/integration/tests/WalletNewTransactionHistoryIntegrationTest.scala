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

    val aliceWalletNewPort = 3007

    "show all types of transactions" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceEntryName = perTestCaseName("alice.cns")
      waitForWalletUser(aliceValidatorWallet)
      val aliceValidatorParty = aliceValidatorWallet.userStatus().party

      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
      val charlieEntryName = perTestCaseName("charlie.cns")
      createDirectoryEntry(charlieUserParty, charlieDirectory, charlieEntryName, charlieWallet)

      createDirectoryEntryForDirectoryItself

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice goes to her wallet", {
            browseToWallet(aliceWalletNewPort, aliceDamlUser)
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
            p2pTransfer(charlieWallet, aliceWallet, aliceUserParty, BigDecimal("1.07"))
            // alice -> charlie
            p2pTransfer(aliceWallet, charlieWallet, charlieUserParty, BigDecimal("1.18"))
            // one-time payment
            val (_, cid, _) = createPaymentRequest(
              aliceWalletBackend.remoteParticipantWithAdminToken,
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
            txs should have size 7
            txs
          },
        )

        inside(txs) {
          case balanceChange +: lockForDirectory +: unlockForDirectory +: directoryCreation +: received +: sent +: otp +: Nil =>
            matchTransaction(balanceChange)(
              coinPrice = 2,
              expectedAction = "Balance Change",
              expectedParty = None,
              expectedAmountCC = BigDecimal(5),
            )
            matchTransaction(lockForDirectory)(
              coinPrice = 2,
              expectedAction = "Automation",
              expectedParty = None,
              expectedAmountCC = BigDecimal("-0.5"), // 1 USD
            )
            matchTransaction(unlockForDirectory)(
              coinPrice = 2,
              expectedAction = "Balance Change",
              expectedParty = None,
              expectedAmountCC = BigDecimal("0.5"), // 1 USD
            )
            matchTransaction(directoryCreation)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedParty = None,
              expectedAmountCC = BigDecimal("-0.5"), // 1 USD
            )
            matchTransaction(received)(
              coinPrice = 2,
              expectedAction = "Received",
              expectedParty = Some(
                s"${expectedCns(charlieUserParty, charlieEntryName)} via ${aliceValidatorParty}"
              ),
              expectedAmountCC = BigDecimal("1.07"),
            )
            matchTransaction(sent)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedParty = Some(
                s"${expectedCns(charlieUserParty, charlieEntryName)} via ${aliceValidatorParty}"
              ),
              expectedAmountCC = BigDecimal("-1.18"),
            )
            matchTransaction(otp)(
              coinPrice = 2,
              expectedAction = "Automation", // Actually OTP, but we cannot distinguish
              expectedParty = None,
              expectedAmountCC = BigDecimal("-1.31415"),
            )
        }
      }
    }

  }

}
