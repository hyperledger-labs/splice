package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.LocalAuth0Test
import org.lfdecentralizedtrust.splice.auth.AuthConfig.Rs256
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllValidatorConfigs_
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{AnsFrontendTestUtil, FrontendLoginUtil, WalletTestUtil}
import monocle.macros.syntax.lens.*

import java.net.URI

class AnsAuth0FrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil
    with AnsFrontendTestUtil
    with FrontendLoginUtil {

  // The change of the authyority appears to break the JSON API and causes "The supplied authentication is invalid"
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransform((_, c) =>
        updateAllValidatorConfigs_(conf =>
          conf
            .focus(_.auth)
            .replace(
              Rs256(
                sys.env("OIDC_AUTHORITY_VALIDATOR_AUDIENCE"),
                new URI(
                  s"https://${sys.env("SPLICE_OAUTH_TEST_AUTHORITY")}/.well-known/jwks.json"
                ).toURL,
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
