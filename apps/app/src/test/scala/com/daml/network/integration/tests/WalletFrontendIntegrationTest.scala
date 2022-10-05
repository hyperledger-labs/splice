package com.daml.network.integration.tests

import scala.concurrent.duration.DurationInt

class WalletFrontendIntegrationTest extends FrontendIntegrationTest {

  "A wallet UI" should {

    "allow tapping coins and then list the created coins" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)

      go to "http://localhost:3000"
      click on "user-id-field"
      textField("user-id-field").value = aliceDamlUser
      click on "login-button"
      click on "tap-amount-field"
      textField("tap-amount-field").value = "15.0"
      click on "tap-button"
      eventually(scaled(5 seconds)) {
        findAll(className("coins-table-row")) should have size 1
      }
      val row = inside(findAll(className("coins-table-row")).toList) { case Seq(row) =>
        row
      }
      val quantity = row.childElement(className("coins-table-quantity"))
      quantity.text should be("15.0000000000")
    }

    "report errors" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)

      go to "http://localhost:3000"
      click on "user-id-field"
      textField("user-id-field").value = aliceDamlUser
      click on "login-button"
      click on "tap-amount-field"
      textField("tap-amount-field").value = "non-numeric"
      loggerFactory.suppressErrors(click on "tap-button")
      eventually()(
        findAll(id("error")).toList should not be empty
      )
      consumeError("RpcError: Could not read Numeric string \"non-numeric\"")
    }
  }
}
