package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.auth.AuthConfig.Rs256
import com.daml.network.config.ConfigTransforms.updateAllValidatorConfigs_
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.util.{FrontendLoginUtil, AnsFrontendTestUtil, WalletTestUtil}
import monocle.macros.syntax.lens.*

import java.net.URL

class AnsAuth0FrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil
    with AnsFrontendTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransform((_, c) =>
        updateAllValidatorConfigs_(conf =>
          conf
            .focus(_.auth)
            .replace(
              Rs256(
                "https://canton.network.global",
                new URL("https://canton-network-test.us.auth0.com/.well-known/jwks.json"),
              )
            )
        )(c)
      )

  "A Name Service UI" should {
    "allow login via auth0" taggedAs LocalAuth0Test in { implicit env =>
      // onboard the user through the wallet UI since the console command refs are not set up to get tokens from the auth0 test tenant -- then log in to the directory UI
      withAuth0LoginCheck("alice", aliceWalletUIPort, onboardThroughWalletUI = true) {
        (user, _, wd) =>
          implicit val webDriver: WebDriverType = wd

          clue("The user logs in with OAauth2 and completes all Auth0 login prompts") {
            completeAuth0LoginWithAuthorization(
              s"http://localhost:$aliceAnsUIPort",
              user.email,
              user.password,
              () => seleniumText(find(id("logged-in-user"))) should not be empty,
            )
          }
      }
    }
  }
}
