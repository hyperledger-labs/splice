package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, SvTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

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
          _ => find(id("app-title")).value.text should matchText("SUPER VALIDATOR OPERATIONS"),
        )
      }
    }

    "warn if user fails to login" in { _ =>
      withFrontEnd("sv1") { implicit webDriver =>
        loggerFactory.assertLogs(
          {
            actAndCheck(
              "login does not work with wrong password", {
                login(port, "NobodyCares!")
              },
            )(
              "login fails",
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

    "can view median coin price and update desired coin price by each SV" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "sv1 operator can login and browse to the coin price tab", {
            go to s"http://localhost:$port/cc-price"
            loginOnCurrentPage(port, sv1.config.ledgerApiUser)
          },
        )(
          "We see a median coin price, desired coin price of SV1 and other SVs, open mining rounds",
          _ => {
            inside(find(id("median-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "1 USD"
            }
            inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "1 USD"
            }
            val rows = findAll(className("coin-price-table-row")).toSeq
            rows should have size 3
            svCoinPriceShouldMatch(rows, sv2.getSvcInfo().svParty, "Not Set")
            svCoinPriceShouldMatch(rows, sv3.getSvcInfo().svParty, "Not Set")
            svCoinPriceShouldMatch(rows, sv4.getSvcInfo().svParty, "Not Set")

            val roundRows = findAll(className("open-mining-round-row")).toSeq
            roundRows should have size 3
            forEvery(roundRows) {
              _.childElement(className("coin-price")).text shouldBe "1 USD"
            }
          },
        )

        actAndCheck(
          "sv1 operator can change the desired price", {
            click on "edit-coin-price-button"
            click on "desired-coin-price-field"
            numberField("desired-coin-price-field").underlying.clear()
            numberField("desired-coin-price-field").underlying.sendKeys("10")

            click on "update-coin-price-button"
          },
        )(
          "median coin price changed and desired coin price of sv1 is updated",
          _ => {
            inside(find(id("median-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "10 USD"
            }
            inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "10 USD"
            }
            val rows = findAll(className("coin-price-table-row")).toSeq
            rows should have size 3
            svCoinPriceShouldMatch(rows, sv2.getSvcInfo().svParty, "Not Set")
            svCoinPriceShouldMatch(rows, sv3.getSvcInfo().svParty, "Not Set")
            svCoinPriceShouldMatch(rows, sv4.getSvcInfo().svParty, "Not Set")
          },
        )

        actAndCheck(
          "sv2 set the desired price", {
            sv2.updateCoinPriceVote(BigDecimal(15.55))
          },
        )(
          "median coin price changed and coin price updated on the row for sv2",
          _ => {
            inside(find(id("median-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "12.775 USD"
            }
            inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "10 USD"
            }
            val rows = findAll(className("coin-price-table-row")).toSeq
            rows should have size 3
            svCoinPriceShouldMatch(rows, sv2.getSvcInfo().svParty, "15.55 USD")
            svCoinPriceShouldMatch(rows, sv3.getSvcInfo().svParty, "Not Set")
            svCoinPriceShouldMatch(rows, sv4.getSvcInfo().svParty, "Not Set")
          },
        )

        actAndCheck(
          "sv3 set the desired price", {
            sv3.updateCoinPriceVote(BigDecimal(5))
          },
        )(
          "median coin price changed and coin price updated on the row for sv2",
          _ => {
            inside(find(id("median-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "10 USD"
            }
            inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "10 USD"
            }
            val rows = findAll(className("coin-price-table-row")).toSeq
            rows should have size 3
            svCoinPriceShouldMatch(rows, sv2.getSvcInfo().svParty, "15.55 USD")
            svCoinPriceShouldMatch(rows, sv3.getSvcInfo().svParty, "5 USD")
            svCoinPriceShouldMatch(rows, sv4.getSvcInfo().svParty, "Not Set")
          },
        )
        actAndCheck(
          "sv4 set the desired price", {
            sv4.updateCoinPriceVote(BigDecimal(9.0))
          },
        )(
          "median coin price changed and coin price updated on the row for sv4",
          _ => {
            inside(find(id("median-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "9.5 USD"
            }
            inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "10 USD"
            }
            val rows = findAll(className("coin-price-table-row")).toSeq
            rows should have size 3
            svCoinPriceShouldMatch(rows, sv2.getSvcInfo().svParty, "15.55 USD")
            svCoinPriceShouldMatch(rows, sv3.getSvcInfo().svParty, "5 USD")
            svCoinPriceShouldMatch(rows, sv4.getSvcInfo().svParty, "9 USD")
          },
        )
        actAndCheck(
          "sv1 update the desired price", {
            sv1.updateCoinPriceVote(BigDecimal(1.0))
          },
        )(
          "median coin price changed",
          _ => {
            inside(find(id("median-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "7 USD"
            }
            inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "1 USD"
            }
            val rows = findAll(className("coin-price-table-row")).toSeq
            rows should have size 3
            svCoinPriceShouldMatch(rows, sv2.getSvcInfo().svParty, "15.55 USD")
            svCoinPriceShouldMatch(rows, sv3.getSvcInfo().svParty, "5 USD")
            svCoinPriceShouldMatch(rows, sv4.getSvcInfo().svParty, "9 USD")
          },
        )
      }
    }
  }

  private def svCoinPriceShouldMatch(
      rows: Seq[Element],
      svParty: PartyId,
      coinPrice: String,
  ) = {
    forExactly(1, rows) { row =>
      row.childElement(className("sv-party")).text shouldBe svParty.toProtoPrimitive
      row.childElement(className("coin-price")).text shouldBe coinPrice
    }
  }
}
