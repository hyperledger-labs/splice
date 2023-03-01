package com.daml.network.util

import com.daml.network.integration.tests.{CoinTests, FrontendTestCommon}
import com.digitalasset.canton.topology.PartyId
import org.openqa.selenium.WebDriver

import scala.util.Using

trait FrontendLoginUtil { self: FrontendTestCommon =>

  protected def login(port: Int, ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    go to s"http://localhost:$port"
    waitForQuery(id("user-id-field"))
    loginOnCurrentPage(ledgerApiUser)
  }

  protected def loginOnCurrentPage(ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    click on "user-id-field"
    textField("user-id-field").value = ledgerApiUser
    click on "login-button"
  }

  protected def browseToWallet(port: Int, ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    actAndCheck(
      s"Browse to wallet UI at port ${port}", {
        login(port, ledgerApiUser)
      },
    )(
      "Logged in user shows up",
      _ => find(id("logged-in-user")).getOrElse(fail("Logged-in user information never showed up")),
    )
  }

  protected def browseToAliceWallet(ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    browseToWallet(3000, ledgerApiUser)
  }

  protected def browseToBobWallet(ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    browseToWallet(3001, ledgerApiUser)
  }

  protected def browseToPaymentRequests(ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    // Go to app payment requests tab in alice's wallet
    browseToAliceWallet(ledgerApiUser)
    click on "app-payment-requests-button"
  }

  protected def browseToSubscriptions(ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    // Go to subscriptions tab in alice's wallet
    browseToAliceWallet(ledgerApiUser)
    click on "subscriptions-button"
  }

  protected def withAuth0LoginCheck[A](frontendDriverName: String, localHostPort: Int)(
      afterLoginChecks: (PartyId, WebDriverType) => A
  )(implicit env: CoinTests.CoinTestConsoleEnvironment): A = {
    val auth0 = auth0UtilFromEnvVars("https://canton-network-test.us.auth0.com")
    Using.resource(retryAuth0Calls(auth0.createUser())) { user =>
      logger.debug(s"Created user ${user.email} with password ${user.password} (id: ${user.id})")
      val userPartyId = aliceValidator.onboardUser(user.id)

      withFrontEnd(frontendDriverName) { implicit webDriver =>
        actAndCheck(
          "The user logs in with OAauth2 and completes all Auth0 login prompts", {
            go to s"http://localhost:$localHostPort"
            click on "oidc-login-button"
            completeAuth0LoginWithAuthorization(user.email, user.password)
          },
        )(
          "The user sees his own party ID in the app",
          _ => find(id("logged-in-user")).value.text should matchText(userPartyId.toProtoPrimitive),
        )

        afterLoginChecks(userPartyId, webDriver)
      }
    }
  }
}
