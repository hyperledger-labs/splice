package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{
  SpliceUtil,
  FrontendLoginUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}

class WalletFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  val amuletPrice = 2
  override def walletAmuletPrice = SpliceUtil.damlDecimal(amuletPrice.toDouble)
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAmuletPrice(amuletPrice)

  "A wallet UI" should {

    "tap" should {

      def onboardAndTapTest(damlUser: String)(implicit env: SpliceTestConsoleEnvironment) = {
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
              eventuallyClickOn(id("onboard-button"))
            },
          )(
            "User is logged in and onboarded",
            _ => {
              userIsLoggedIn()
              waitForQuery(className("party-id"))
            },
          )

          val testTap = (amountUsd: BigDecimal, feeUpperBoundUsd: BigDecimal) => {

            val amount = walletUsdToAmulet(amountUsd)
            val feeUpperBound = walletUsdToAmulet(feeUpperBoundUsd)

            val (ccTextBefore, usdTextBefore) = eventually() {
              val ccTextBefore = find(id("wallet-balance-amulet")).value.text.trim
              val usdTextBefore = find(id("wallet-balance-usd")).value.text.trim
              ccTextBefore should not be "..."
              usdTextBefore should not be "..."
              (ccTextBefore, usdTextBefore)
            }
            val ccBefore = BigDecimal(ccTextBefore.split(" ").head)
            val usdBefore = BigDecimal(usdTextBefore.split(" ").head)

            actAndCheck(
              s"User taps $amount Amulet in the wallet", {
                tapAmulets(amountUsd)
              },
            )(
              "User sees the updated balance",
              _ => {
                val ccText = find(id("wallet-balance-amulet")).value.text.trim
                val usdText = find(id("wallet-balance-usd")).value.text.trim

                ccText should not be "..."
                usdText should not be "..."
                val cc = BigDecimal(ccText.split(" ").head)
                val usd = BigDecimal(usdText.split(" ").head)

                assertInRange(cc - ccBefore, (amount - feeUpperBound, amount))
                assertInRange(
                  usd - usdBefore,
                  ((amount - feeUpperBound) * amuletPrice, amount * amuletPrice),
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
          val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
          onboardAndTapTest(aliceDamlUser)
      }

      "allow a random user with uppercase characters to onboard themselves, then tap and list amulets" in {
        implicit env =>
          val damlUser = "UPPERCASE" + aliceWalletClient.config.ledgerApiUser
          onboardAndTapTest(damlUser)
      }

      "fail when trying to use more than 10 decimal points" in { implicit env =>
        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

        val manyDigits = "1.19191919191919199191"

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice taps balance with more than 10 decimal places in the wallet", {
              browseToAliceWallet(aliceDamlUser)
              eventuallyClickOn(id("tap-amount-field"))
              numberField("tap-amount-field").value = manyDigits
              eventuallyClickOn(id("tap-button"))
            },
          )(
            "Alice has unchanged balance and sees error message",
            _ => {
              import WalletFrontendTestUtil.*
              val ccText = find(id("wallet-balance-amulet")).value.text.trim
              val usdText = find(id("wallet-balance-usd")).value.text.trim
              val errorMessage = find(className(errorDisplayElementClass)).value.text.trim

              ccText should not be "..."
              usdText should not be "..."
              errorMessage should be("Tap operation failed")
              find(className(errorDetailsElementClass)).value.text.trim should
                include(
                  "Failed to decode: Invalid Decimal string \\\"NaN\\\", as it does not match"
                )

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
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice logs in", {
              browseToAliceWallet(aliceWalletClient.config.ledgerApiUser)
            },
          )(
            "Alice is initially NOT featured",
            _ => {
              find(id("featured-status")) should be(None)
            },
          )

          actAndCheck(
            "Alice self-features herself", {
              eventuallyClickOn(id("self-feature"))
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

    "show logged in ANS name" in { implicit env =>
      // Create directory entry for alice
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val entryName = perTestCaseName("alice")

      createAnsEntry(
        aliceAnsExternalClient,
        entryName,
        aliceWalletClient,
      )
      eventuallySucceeds() {
        sv1ScanBackend.lookupEntryByName(entryName)
      }

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice browses to the wallet", {
            browseToAliceWallet(aliceDamlUser)
          },
        )(
          "Alice sees her Name Service entry name",
          _ => {
            seleniumText(find(id("logged-in-user"))) should matchText(entryName)
          },
        )

        actAndCheck(
          "Alice refreshes the page", {
            webDriver.navigate().refresh()
          },
        )(
          "The name is still there",
          _ => {
            seleniumText(find(id("logged-in-user"))) should matchText(entryName)
          },
        )
      }
    }

  }
}
