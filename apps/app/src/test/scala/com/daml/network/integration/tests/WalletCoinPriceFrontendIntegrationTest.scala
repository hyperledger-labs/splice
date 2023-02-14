package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.config.CoinConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletCoinPriceFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "bob")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => CoinConfigTransforms.setCoinPrice(2)(conf))

  "A wallet UI with a coin price of 2.0" should {
    "correctly compute totals for multi-recipient requests with CC and USD" in { implicit env =>
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

        verifyRequestAmountIsDisplayed(32)

        // Verify that the receiver table rows contain both receiver amounts
        verifyReceiverTableAmounts("22.0CC", "20.0USD")
      }
    }
  }
}
