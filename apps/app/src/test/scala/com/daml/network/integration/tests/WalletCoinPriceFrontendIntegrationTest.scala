package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.{payment as paymentCodegen}
import com.daml.network.config.CoinConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletCoinPriceFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvirontment("alice", "bob")
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => CoinConfigTransforms.setCoinPrice(2)(conf))

  "A wallet UI with a coin price of 2.0" should {
    "correctly compute totals for multi-recepient requests with CC and USD" in { implicit env =>
      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)

      createPaymentRequest(
        aliceUserParty,
        Seq(
          receiverAmount(aliceUserParty, 22, paymentCodegen.Currency.CC),
          receiverAmount(aliceUserParty, 20, paymentCodegen.Currency.USD),
        ),
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToPaymentRequests(aliceDamlUser)

        // Verify that the total amount of USD is properly displayed
        eventually() {
          inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
            // Verify that the currency and amount are properly displayed
            row.childElement(className("app-request-total-amount")).text should matchText(
              "32.00000000CC"
            )
          }
        }

        // Verify that the receiver table rows contain both receiver amounts
        eventually() {
          val amounts =
            findAll(className("receiver-amount-row")).toList.map(row =>
              row.childElement(className("app-request-payment-amount")).text
            )

          amounts should contain theSameElementsAs Seq(
            "22.0000000000CC",
            "20.0000000000USD",
          )
        }
      }
    }
  }
}
