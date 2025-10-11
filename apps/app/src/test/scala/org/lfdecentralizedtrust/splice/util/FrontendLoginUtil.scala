package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.{FrontendTestCommon, SpliceTests}
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.util.Auth0Util.WithAuth0Support
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions

import scala.concurrent.duration.DurationInt
import scala.util.Using

trait FrontendLoginUtil extends WithAuth0Support { self: FrontendTestCommon =>

  protected def login(port: Int, ledgerApiUser: String, hostname: String = "localhost")(implicit
      webDriver: WebDriver
  ) = {
    go to s"http://$hostname:$port"
    waitForQuery(id("user-id-field"))
    loginOnCurrentPage(port, ledgerApiUser, hostname)
  }

  protected def loginOnCurrentPage(
      port: Int,
      ledgerApiUser: String,
      hostname: String = "localhost",
  )(implicit
      webDriver: WebDriver
  ) = {
    eventually() {
      val url = if (port == 80) {
        s"http://$hostname"
      } else {
        s"http://$hostname:$port"
      }
      currentUrl should startWith(url)
    }
    loginOnceConfirmedToBeAtUrl(ledgerApiUser)
  }

  protected def loginOnceConfirmedToBeAtUrl(
      ledgerApiUser: String
  )(implicit webDriver: WebDriver) = {
    eventually(timeUntilSuccess = 5.seconds) {
      if (find(id("logout-button")).isDefined) {
        eventuallyClickOn(id("logout-button"))
      }
      silentClue("Waiting for login page to be ready") {
        waitForCondition(id("user-id-field"), timeUntilSuccess = Some(1.seconds)) {
          ExpectedConditions.elementToBeClickable(_)
        }
      }
    }
    textField("user-id-field").value = ledgerApiUser
    eventuallyClickOn(id("login-button"))
  }

  protected def browseToWallet(port: Int, ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    actAndCheck(
      s"Browse to wallet UI at port ${port}", {
        login(port, ledgerApiUser)
      },
    )(
      "Logged in user shows up",
      _ => userIsLoggedIn(),
    )
  }
  protected def userIsLoggedIn()(implicit webDriver: WebDriver) = {
    waitForQuery(id("logged-in-user"))
  }

  protected def browseToAliceWallet(ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    browseToWallet(3000, ledgerApiUser)
  }

  protected def browseToBobWallet(ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    browseToWallet(3001, ledgerApiUser)
  }

  protected def browseToSv1Wallet(ledgerApiUser: String)(implicit webDriver: WebDriver) = {
    browseToWallet(3011, ledgerApiUser)
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

  protected def withAuth0LoginCheck[A](
      frontendDriverName: String,
      localHostPort: Int,
      onboardThroughWalletUI: Boolean = false,
  )(
      afterLoginChecks: (Auth0User, PartyId, WebDriverType) => A
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment): A = {
    val auth0 = auth0UtilFromEnvVars("test")
    Using.resource(auth0.createUser()) { user =>
      if (!onboardThroughWalletUI) {
        aliceValidatorBackend.onboardUser(user.id)
      }

      withFrontEnd(frontendDriverName) { implicit webDriver =>
        clue("The user logs in with OAauth2 and completes all Auth0 login prompts") {
          completeAuth0LoginWithAuthorization(
            s"http://localhost:$localHostPort",
            user.email,
            user.password,
            () =>
              if (onboardThroughWalletUI) {
                find(id("onboard-button")).value.text should not be empty
              } else {
                seleniumText(find(id("logged-in-user"))) should not be empty
              },
          )
        }
        val userPartyId = if (onboardThroughWalletUI) {
          actAndCheck("onboard user", click on "onboard-button")(
            "user is onboarded",
            _ => {
              val userId = seleniumText(find(id("logged-in-user")))
              userId should not be empty
              userId
            },
          )._2
        } else {
          seleniumText(find(id("logged-in-user")))
        }

        afterLoginChecks(user, PartyId.tryFromProtoPrimitive(userPartyId), webDriver)
      }
    }
  }
}
