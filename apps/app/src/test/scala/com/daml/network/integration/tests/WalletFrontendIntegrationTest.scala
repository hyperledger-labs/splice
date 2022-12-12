package com.daml.network.integration.tests

import com.daml.network.util.WalletTestUtil

import scala.util.Try

class WalletFrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil
    with CnsTestUtil {

  "A wallet UI" should {

    "allow tapping coins and then list the created coins" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.damlUser
      onboardWalletUser(aliceWallet, aliceValidator)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        click on "tap-amount-field"
        numberField("tap-amount-field").underlying.sendKeys("15.0")
        click on "tap-button"
        eventually() {
          findAll(className("coins-table-row")) should have size 1
        }
        val row = inside(findAll(className("coins-table-row")).toList) { case Seq(row) => row }
        val quantity = row.childElement(className("coins-table-quantity"))
        quantity.text should be("15.0000000000CC")
      }
    }

    "allow a random user to onboard themselves, then tap and list coins" in { implicit env =>
      // Note: the test generates a unique user for each test
      val newRandomUser = aliceWallet.config.damlUser

      withFrontEnd("alice") { implicit webDriver =>
        // Do not use browseToWallet below, because that waits for the user to be logged in, which is not the case here
        go to s"http://localhost:3000"
        click on "user-id-field"
        textField("user-id-field").value = newRandomUser
        click on "login-button"

        // After a short delay, the UI should realize that the user is not onboarded,
        // and switch to the onbaording page.
        click on "onboard-button"
        // The onboard button should immediately be disabled, to prevent further clicking.
        find(id("onboard-button")) match {
          case Some(e) => e.isEnabled shouldBe false
          case _ => // The page went back to the default view before we could check the button
        }

        // After a short delay, the UI should realize that the user is now onboarded
        // and switch to the default view.
        click on "tap-amount-field"
        numberField("tap-amount-field").underlying.sendKeys("15.0")
        click on "tap-button"
        eventually() {
          findAll(className("coins-table-row")) should have size 1
        }
      }
    }

    "show logged in user details" in { implicit env =>
      // Create directory entry for alice
      val aliceDamlUser = aliceWallet.config.damlUser
      val entryName = "alice.cns"
      val aliceParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      requestDirectoryEntry(aliceParty, aliceDirectory, entryName)

      def getPaymentRequest() = aliceWallet.listSubscriptionRequests().headOption

      aliceWallet.tap(5.0)
      val subscriptionRequest = eventually()(
        getPaymentRequest().getOrElse(fail("Payment request is unexpectedly not defined"))
      )
      val _ = aliceWallet.acceptSubscriptionRequest(subscriptionRequest.contractId)

      def tryGetEntry() =
        Try(loggerFactory.suppressErrors(directory.lookupEntryByName(entryName)))

      eventually()(tryGetEntry().getOrElse(fail(s"Could not get entry $entryName")))

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        // Check that alice is shown as the user, and her party ID has been resolved to its directory entry correctly.
        // We do this in another eventually() as a "..." text might appear momentarily, until the directory service responds.
        eventually() {
          find(id("logged-in-user")).value.text should matchText(
            expectedCns(aliceParty, "alice.cns")
          )
        }
      }
    }

    "user name is persisted" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWallet, aliceValidator)
      withFrontEnd("alice") { implicit webDrivers =>
        browseToAliceWallet(aliceWallet.config.damlUser)
        find(id("logged-in-user")).value.text should matchText(aliceParty.toProtoPrimitive)
        actAndCheck(
          "Alice reloads the page", {
            go to s"http://localhost:3000"
          },
        )(
          "Alice is automatically logged in",
          _ => find(id("logged-in-user")).value.text should matchText(aliceParty.toProtoPrimitive),
        )
        actAndCheck(
          "Alice logs out", {
            click on "logout-button"
          },
        )("Alice sees the login screen again", _ => find(id("login-button")) should not be empty)
      }
    }
  }
}
