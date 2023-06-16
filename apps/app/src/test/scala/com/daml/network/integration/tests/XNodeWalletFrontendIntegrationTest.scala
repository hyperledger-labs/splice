package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class XNodeWalletFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  val coinPrice = 2
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyX(this.getClass.getSimpleName)
      .withCoinPrice(coinPrice)

  "A wallet UI" should {

    "tap" should {

      def onboardAndTapTest(damlUser: String) = {
        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "User logs in", {
              // Do not use browseToWallet below, because that waits for the user to be logged in, which is not the case here
              login(3000, damlUser)
            },
          )(
            "User sees the onboarding page",
            _ => {
              // After a short delay, the UI should realize that the user is not onboarded,
              // and switch to the onbaording page.
              waitForQuery(id("onboard-button"))
            },
          )

          actAndCheck(
            "User onboards themselves", {
              click on "onboard-button"
            },
          )(
            "User is logged in and onboarded",
            _ => {
              userIsLoggedIn()
            },
          )

          val testTap = (amount: BigDecimal, feeUpperBound: BigDecimal) => {

            val (ccTextBefore, usdTextBefore) = eventually() {
              val ccTextBefore = find(id("wallet-balance-cc")).value.text.trim
              val usdTextBefore = find(id("wallet-balance-usd")).value.text.trim
              ccTextBefore should not be "..."
              usdTextBefore should not be "..."
              (ccTextBefore, usdTextBefore)
            }
            val ccBefore = BigDecimal(ccTextBefore.split(" ").head)
            val usdBefore = BigDecimal(usdTextBefore.split(" ").head)

            actAndCheck(
              s"User taps $amount in the wallet", {
                tapCoins(amount)
              },
            )(
              "User sees the updated balance",
              _ => {
                val ccText = find(id("wallet-balance-cc")).value.text.trim
                val usdText = find(id("wallet-balance-usd")).value.text.trim

                ccText should not be "..."
                usdText should not be "..."
                val cc = BigDecimal(ccText.split(" ").head)
                val usd = BigDecimal(usdText.split(" ").head)

                assertInRange(cc - ccBefore, (amount - feeUpperBound, amount))
                assertInRange(
                  usd - usdBefore,
                  ((amount - feeUpperBound) * coinPrice, amount * coinPrice),
                )
              },
            )

          }

          testTap(2, smallAmount)
          testTap(3.14159, 0.05)

        }
      }

      "allow a random user to onboard themselves and show updated balances after tapping" in {
        implicit env =>
          val aliceDamlUser = aliceWallet.config.ledgerApiUser
          onboardAndTapTest(aliceDamlUser)
      }

      "allow a random user with uppercase characters to onboard themselves, then tap and list coins" in {
        implicit env =>
          val damlUser = "UPPERCASE" + aliceWallet.config.ledgerApiUser
          onboardAndTapTest(damlUser)
      }

      "fail when trying to use more than 10 decimal points" in { implicit env =>
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        onboardWalletUser(aliceWallet, aliceValidator)

        val manyDigits = "1.19191919191919199191"

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice taps balance with more than 10 decimal places in the wallet", {
              browseToAliceWallet(aliceDamlUser)
              click on "tap-amount-field"
              numberField("tap-amount-field").value = manyDigits
              click on "tap-button"
            },
          )(
            "Alice has unchanged balance and sees error message",
            _ => {
              val ccText = find(id("wallet-balance-cc")).value.text.trim
              val usdText = find(id("wallet-balance-usd")).value.text.trim
              val errorMessage = find(className("error-display-text")).value.text.trim

              ccText should not be "..."
              usdText should not be "..."
              errorMessage should be("Tap operation failed")

              val cc = BigDecimal(ccText.split(" ").head)
              val usd = BigDecimal(usdText.split(" ").head)

              cc shouldBe BigDecimal(0)
              usd shouldBe BigDecimal(0)
            },
          )
        }
      }

    }

    "featured app rights" should {

      "show featured status and support self-featuring" in { implicit env =>
        onboardWalletUser(aliceWallet, aliceValidator)

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice logs in", {
              browseToAliceWallet(aliceWallet.config.ledgerApiUser)
            },
          )(
            "Alice is initially NOT featured",
            _ => {
              find(id("featured-status")) should be(None)
            },
          )

          actAndCheck(
            "Alice self-features herself", {
              click on "self-feature"
            },
          )(
            "Alice sees herself as featured",
            _ => {
              find(id("self-feature")) should be(None)
              find(id("featured-status")).valueOrFail("Not featured!")
            },
          )

          actAndCheck(
            "Alice refreshes the page", {
              webDriver.navigate().refresh()
            },
          )(
            "Alice is still featured",
            _ => {
              find(id("self-feature")) should be(None)
              find(id("featured-status")).valueOrFail("Not featured anymore!")
            },
          )
        }

      }

    }

    "show logged in directory name" in { implicit env =>
      // Create directory entry for alice
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val entryName = perTestCaseName("alice.cns")
      val aliceParty = setupForTestWithDirectory(aliceWallet, aliceValidator)

      createDirectoryEntry(
        aliceParty,
        aliceDirectory,
        entryName,
        aliceWallet,
      )
      eventuallySucceeds() {
        directory.lookupEntryByName(entryName)
      }

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice browses to the wallet", {
            browseToAliceWallet(aliceDamlUser)
          },
        )(
          "Alice sees her directory entry name",
          _ => {
            find(id("logged-in-user")).value.text should matchText(entryName)
          },
        )

        actAndCheck(
          "Alice refreshes the page", {
            webDriver.navigate().refresh()
          },
        )(
          "The name is still there",
          _ => {
            find(id("logged-in-user")).value.text should matchText(entryName)
          },
        )
      }
    }

  }
}
