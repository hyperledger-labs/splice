package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.auth.AuthConfig.Rs256
import com.daml.network.config.CNNodeConfigTransforms.updateAllWalletAppBackendConfigs_
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.FrontendLoginUtil
import monocle.macros.syntax.lens.*

import java.net.URL

class WalletAuth0FrontendIntegrationTest
    extends FrontendIntegrationTest("randomUser")
    with FrontendLoginUtil {

  override def environmentDefinition = {
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, cnNodeConfig) =>
        updateAllWalletAppBackendConfigs_(walletConfig =>
          walletConfig
            .focus(_.auth)
            .replace(
              Rs256(
                "https://canton.network.global",
                new URL("https://canton-network-test.us.auth0.com/.well-known/jwks.json"),
              )
            )
        )(cnNodeConfig)
      )
  }

  "A wallet UI with a backend configured to accept auth0 tokens" should {

    "allow login via auth0 and persist user name on refresh" taggedAs LocalAuth0Test in {
      implicit env =>
        withAuth0LoginCheck("randomUser", 3000) { (userPartyId, wd) =>
          implicit val webDriver: WebDriverType = wd
          actAndCheck(
            "The user reloads the page", {
              go to s"http://localhost:3000"
            },
          )(
            "The user is automatically logged in",
            _ =>
              find(id("logged-in-user")).value.text should matchText(userPartyId.toProtoPrimitive),
          )
          actAndCheck(
            "The user logs out", {
              click on "logout-button"
            },
          )(
            "The user sees the login screen again",
            _ => find(id("oidc-login-button")) should not be empty,
          )
        }
    }
  }
}
