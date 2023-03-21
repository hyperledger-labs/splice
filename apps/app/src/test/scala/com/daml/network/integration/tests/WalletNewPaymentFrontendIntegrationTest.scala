package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.codegen.java.cn.wallet.payment.Currency
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletNewFrontendTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletNewPaymentFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "bob")
    with WalletTestUtil
    with WalletNewFrontendTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withCoinPrice(2)

  "A wallet payments UI" should {

    val aliceWalletNewPort = 3007

    "for single receiver" should {

      "allow accepting payments in CC" in { implicit env =>
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        val aliceEntryName = perTestCaseName("alice.cns")
        createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)

        val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
        val charlieEntryName = perTestCaseName("charlie.cns")
        createDirectoryEntry(charlieUserParty, charlieDirectory, charlieEntryName, charlieWallet)

        val (_, paymentRequestContractId, _) = createPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(charlieUserParty, BigDecimal("1.5"), paymentCodegen.Currency.CC)
          ),
        )

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice goes to the page to confirm the payment", {
              go to s"http://localhost:$aliceWalletNewPort/confirm-payment/${paymentRequestContractId.contractId}"
              loginOnCurrentPage(aliceWalletNewPort, aliceDamlUser)
            },
          )(
            "Alice sees the payment information",
            _ => {
              matchPaymentInfo(id("confirm-payment").element)(
                expectedBalance =
                  "Total Available Balance: 4.4475 CC / 8.895 USD", // from the self-directory creation
                expectedSendAmount = "1.5" -> Currency.CC,
                expectedReceiver = expectedCns(charlieUserParty, charlieEntryName),
                expectedProvider = expectedCns(aliceUserParty, aliceEntryName),
                expectedTotalCC = "1.5",
                expectedComputeText = "1.5 CC + 0 CC fee / 3 USD",
              )
            },
          )

          actAndCheck(
            "Alice clicks on the button to confirm the payment", {
              click on className("payment-accept")
              go to s"http://localhost:$aliceWalletNewPort"
            },
          )(
            "The payment is processed",
            _ => {
              val tx = findAll(className("tx-row")).toSeq.last

              matchTransaction(tx)(2, "Automation", None, BigDecimal("-1.5"))

              matchBalance("2.885", "5.77")
            },
          )

        }
      }

      "allow accepting payments in USD" in { implicit env =>
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        val aliceEntryName = perTestCaseName("alice.cns")
        createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)

        val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
        val charlieEntryName = perTestCaseName("charlie.cns")
        createDirectoryEntry(charlieUserParty, charlieDirectory, charlieEntryName, charlieWallet)

        val (_, paymentRequestContractId, _) = createPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(charlieUserParty, BigDecimal("5.5"), paymentCodegen.Currency.USD)
          ),
        )

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice goes to the page to confirm the payment", {
              go to s"http://localhost:$aliceWalletNewPort/confirm-payment/${paymentRequestContractId.contractId}"
              loginOnCurrentPage(aliceWalletNewPort, aliceDamlUser)
            },
          )(
            "Alice sees the payment information",
            _ => {
              matchPaymentInfo(id("confirm-payment").element)(
                expectedBalance =
                  "Total Available Balance: 4.4475 CC / 8.895 USD", // from the self-directory creation
                expectedSendAmount = "5.5" -> Currency.USD,
                expectedReceiver = expectedCns(charlieUserParty, charlieEntryName),
                expectedProvider = expectedCns(aliceUserParty, aliceEntryName),
                expectedTotalCC = "2.75",
                expectedComputeText = "2.75 CC + 0 CC fee / 5.5 USD",
              )
            },
          )

          actAndCheck(
            "Alice clicks on the button to confirm the payment", {
              click on className("payment-accept")
              go to s"http://localhost:$aliceWalletNewPort"
            },
          )(
            "The payment is processed",
            _ => {
              val tx = findAll(className("tx-row")).toSeq.last

              matchTransaction(tx)(2, "Automation", None, BigDecimal("-2.75"))

              matchBalance("1.6225", "3.245")
            },
          )

        }
      }

    }

  }

  private def matchPaymentInfo(element: Element)(
      expectedBalance: String,
      expectedSendAmount: (String, Currency),
      expectedReceiver: String,
      expectedProvider: String,
      expectedTotalCC: String,
      expectedComputeText: String,
      expectedDescription: String = "Payment Desc.", // TODO: (#3304) check the description
  ) = {
    element.childElement(className("available-balance")).text should matchText(expectedBalance)

    element.childElement(className("payment-amount-receiver")).text should matchText(
      s"Send ${expectedSendAmount._1} ${expectedSendAmount._2} to $expectedReceiver"
    )

    element.childElement(className("payment-provider")).text should matchText(
      s"via $expectedProvider"
    )

    element.childElement(className("payment-description")).text should matchText(
      s"\"$expectedDescription\""
    )

    // TODO (#3492): test with fee
    element.childElement(className("payment-total-cc")).text should matchText(
      s"$expectedTotalCC CC"
    )

    element.childElement(className("payment-compute")).text should matchText(expectedComputeText)
  }

}
