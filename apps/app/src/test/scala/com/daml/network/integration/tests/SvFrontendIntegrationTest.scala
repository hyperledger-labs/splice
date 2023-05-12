package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, SvTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SvFrontendIntegrationTest
    extends FrontendIntegrationTest("sv1")
    with SvTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  "A SV UI" should {
    val port = 3010

    "have basic login functionality" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "login works with correct password", {
            login(port, sv1.config.ledgerApiUser)
          },
        )(
          "logged in in the sv ui",
          _ => find(id("app-title")).value.text should matchText("SV OPERATIONS"),
        )
      }
    }

    "warn if user fails to login" in { _ =>
      withFrontEnd("sv1") { implicit webDriver =>
        loggerFactory.assertLogs(
          {
            actAndCheck(
              "login works with correct password", {
                login(port, "NobodyCares!")
              },
            )(
              "login does not work with wrong password",
              _ => find(id("loginFailed")).value.text should matchText("User failed to login!"),
            )
          },
          _.warningMessage should include(
            "Authorization Failed"
          ),
        )
      }
    }

    "have a proper table" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "svc infos are displayed", {
            login(port, sv1.config.ledgerApiUser)
          },
        )(
          "We see a table with sv1 as SV Name",
          _ => {
            val rows = findAll(className("value-name")).toSeq
            rows should have length 15
            forExactly(1, rows)(
              _.text should matchText(sv1.getSvcInfo().svUser)
            )
            forExactly(3, rows)(
              _.text should matchText(sv1.getSvcInfo().svParty.toProtoPrimitive)
            )
          },
        )
      }
    }

    "can prepare an onboarding secret for new validator" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        val (_, rowSize) = actAndCheck(
          "sv1 operator can login and browse to the validator onboarding tab", {
            go to s"http://localhost:$port/validator-onboarding"
            loginOnCurrentPage(port, sv1.config.ledgerApiUser)
          },
        )(
          "We see a button for creating onboarding secret",
          _ => {
            find(className("onboarding-secret-table")) should not be empty
            val rows = findAll(className("onboarding-secret-table-row")).toSeq
            find(id("create-validator-onboarding-secret")) should not be empty
            rows.size
          },
        )

        val (_, newSecret) = actAndCheck(
          "click on the button to create an onboarding secret", {
            click on "create-validator-onboarding-secret"
          },
        )(
          "a new secret row is added",
          _ => {
            val secrets = findAll(
              className("onboarding-secret-table-secret")
            ).toSeq
            secrets should have size (rowSize + 1L)
            secrets.head.text
          },
        )

        val licenseRows = findAll(className("validator-licenses-table-row")).toList
        val newValidatorParty = allocateRandomSvParty("validatorX")

        actAndCheck(
          "onboard new validator using the secret",
          sv1.onboardValidator(newValidatorParty, newSecret),
        )(
          "a new validator row is added",
          _ => {
            val newLicenseRows = findAll(className("validator-licenses-table-row")).toList
            newLicenseRows should have size (licenseRows.size + 1L)
            val row: Element = inside(newLicenseRows) { case row :: _ =>
              row
            }
            val sponsor =
              row.childElement(className("validator-licenses-sponsor")).text
            val validator =
              row.childElement(className("validator-licenses-validator")).text
            sponsor shouldBe sv1.getSvcInfo().svParty.toProtoPrimitive
            validator shouldBe newValidatorParty.toProtoPrimitive
          },
        )
      }
    }

    "can view median coin price and desired coin price by each SV" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "sv1 operator can login and browse to the coin price tab", {
            go to s"http://localhost:$port/cc-price"
            loginOnCurrentPage(port, sv1.config.ledgerApiUser)
          },
        )(
          "We see a median coin price, desired coin price of SV1 and other SVs",
          _ => {
            inside(find(id("median-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "1 USD"
            }
            inside(find(id("cur-sv-coin-price"))) { case Some(e) =>
              e.text shouldBe "Your Desired Canton Coin Price : 1 USD"
            }
            findAll(className("coin-price-table-row")).toSeq should have size 3
            // TODO(#4495) add more assertion on coin price value when SV operator is allows to set desired coin price
          },
        )
      }
    }
  }
}
