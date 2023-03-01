package com.daml.network.integration.tests

import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil, WalletTestUtil}

class WalletNewFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  "A wallet UI" should {

    "allow logging in & logging out" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      onboardWalletUser(aliceWallet, aliceValidator)
      withFrontEnd("alice") { implicit webDriver =>
        browseToWallet(3007, aliceDamlUser)
        actAndCheck(
          "Alice logs out", {
            click on "logout-button"
          },
        )("Alice sees the login screen again", _ => find(id("login-button")) should not be empty)
      }
    }

  }
}
