package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.{
  FrontendLoginUtil,
  TimeTestUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration

class WalletNewFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => CNNodeConfigTransforms.setCoinPrice(2)(conf))

  "A wallet UI" should {

    val aliceWalletNewPort = 3007

    "onboard a new user" in { implicit env =>
      // Note: the test generates a unique user for each test
      val newRandomUser = aliceWallet.config.ledgerApiUser

      withFrontEnd("alice") { implicit webDriver =>
        login(3000, newRandomUser)

        // After a short delay, the UI should realize that the user is not onboarded,
        // and switch to the onbaording page.
        click on "onboard-button"
        // The onboard button should immediately be disabled, to prevent further clicking.
        find(id("onboard-button")) match {
          case Some(e) => e.isEnabled shouldBe false
          case _ => // The page went back to the default view before we could check the button
        }

        userIsLoggedIn()
      }
    }

    "allow logging in & logging out" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      onboardWalletUser(aliceWallet, aliceValidator)
      withFrontEnd("alice") { implicit webDriver =>
        browseToWallet(aliceWalletNewPort, aliceDamlUser)
        actAndCheck(
          "Alice logs out", {
            click on "logout-button"
          },
        )("Alice sees the login screen again", _ => find(id("login-button")) should not be empty)
      }
    }

    "show balances after login" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(2)
      lockCoins(
        aliceWalletBackend,
        aliceParty,
        aliceValidator.getValidatorPartyId(),
        aliceWallet.list().coins,
        BigDecimal(1),
        scan,
        Duration.ofDays(1),
      )
      withFrontEnd("alice") { implicit webDriver =>
        browseToWallet(aliceWalletNewPort, aliceDamlUser)

        eventually() {
          val ccText = find(id("wallet-balance-cc")).value.text.trim
          val usdText = find(id("wallet-balance-usd")).value.text.trim

          ccText should not be "..."
          usdText should not be "..."
          val cc = BigDecimal(ccText.split(" ").head)
          val usd = BigDecimal(usdText.split(" ").head)

          assertInRange(cc, (BigDecimal(1.9), BigDecimal(2)))
          assertInRange(usd, (BigDecimal(3.9), BigDecimal(4)))
        }
      }
    }
  }
}
