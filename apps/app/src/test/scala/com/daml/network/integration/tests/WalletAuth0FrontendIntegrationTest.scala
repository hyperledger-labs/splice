package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.auth.AuthConfig.Rs256
import com.daml.network.config.CoinConfigTransforms.updateAllWalletAppBackendConfigs_
import com.daml.network.integration.CoinEnvironmentDefinition
import monocle.macros.syntax.lens.*

import java.net.URL
import scala.util.Using

class WalletAuth0FrontendIntegrationTest extends FrontendIntegrationTest("randomUser") {

  override def environmentDefinition = {
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, coinConfig) =>
        updateAllWalletAppBackendConfigs_(walletConfig =>
          walletConfig
            .focus(_.auth)
            .replace(
              Rs256(new URL("https://canton-network-test.us.auth0.com/.well-known/jwks.json"))
            )
        )(coinConfig)
      )
  }

  "A wallet UI with a backend configured to accept auth0 tokens" should {

    "allow login via auth0 and persist user name on refresh" taggedAs LocalAuth0Test in {
      implicit env =>
        val auth0 = auth0UtilFromSystemPoperties("https://canton-network-test.us.auth0.com")
        Using.resource(auth0.createUser()) { user =>
          logger.debug(
            s"Created user ${user.email} with password ${user.password} (id: ${user.id})"
          )
          val userPartyId = aliceValidator.onboardUser(user.id)

          withFrontEnd("randomUser") { implicit webDriver =>
            actAndCheck(
              "The user logs in with OAauth2 and completes all Auth0 login prompts", {
                go to "http://localhost:3000"
                click on "oidc-login-button"
                completeAuth0LoginWithAuthorization(user.email, user.password)
              },
            )(
              "The user sees his own party ID in the app",
              _ =>
                find(id("logged-in-user")).value.text should matchText(userPartyId.toProtoPrimitive),
            )
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
}
