package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletNewFrontendTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletNewTapFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletNewFrontendTestUtil
    with FrontendLoginUtil {

  val coinPrice = 2
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withCoinPrice(coinPrice)

  "A wallet UI" should {

    val aliceWalletNewPort = 3007

    "show updated balances after tapping" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      onboardWalletUser(aliceWallet, aliceValidator)

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice taps balance in the wallet", {
            browseToWallet(aliceWalletNewPort, aliceDamlUser)
            tapCoins(2)
          },
        )(
          "Alice sees the updated balance",
          _ => {
            val ccText = find(id("wallet-balance-cc")).value.text.trim
            val usdText = find(id("wallet-balance-usd")).value.text.trim

            ccText should not be "..."
            usdText should not be "..."
            val cc = BigDecimal(ccText.split(" ").head)
            val usd = BigDecimal(usdText.split(" ").head)

            assertInRange(cc, (BigDecimal(2) - smallAmount, BigDecimal(2)))
            assertInRange(
              usd,
              ((BigDecimal(2) - smallAmount) * coinPrice, BigDecimal(2) * coinPrice),
            )
          },
        )
      }
    }

    "fail when trying to use more than 10 decimal points" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      onboardWalletUser(aliceWallet, aliceValidator)

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice taps balance with more than 10 decimal places in the wallet", {
            browseToWallet(aliceWalletNewPort, aliceDamlUser)
            loggerFactory.suppressErrors(
              tapCoins(BigDecimal("3.141592653589793238462643383279502884197"))
            )
          },
        )(
          "Alice has unchanged balance",
          _ => {
            val ccText = find(id("wallet-balance-cc")).value.text.trim
            val usdText = find(id("wallet-balance-usd")).value.text.trim

            ccText should not be "..."
            usdText should not be "..."
            val cc = BigDecimal(ccText.split(" ").head)
            val usd = BigDecimal(usdText.split(" ").head)

            cc shouldBe BigDecimal(0)
            usd shouldBe BigDecimal(0)
          },
        )
      }
    }

  }
}
