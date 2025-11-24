package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.LocalAuth0Test
import org.lfdecentralizedtrust.splice.auth.AuthConfig.Rs256
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.Unit
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllValidatorConfigs_
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import monocle.macros.syntax.lens.*

import java.net.URI

class WalletAuth0FrontendIntegrationTest
    extends FrontendIntegrationTest("randomUser")
    with FrontendLoginUtil
    with WalletTestUtil
    with WalletFrontendTestUtil {

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransform((_, spliceConfig) =>
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
        )(spliceConfig)
      )
  }

  "A wallet UI with a backend configured to accept auth0 tokens" should {
    "allow login via auth0 and persist user name on refresh" taggedAs LocalAuth0Test in {
      implicit env =>
        withAuth0LoginCheck("randomUser", 3000, onboardThroughWalletUI = true) {
          (_, userPartyId, wd) =>
            implicit val webDriver: WebDriverType = wd
            actAndCheck(
              "The user reloads the page", {
                go to s"http://localhost:3000"
              },
            )(
              "The user is automatically logged in",
              _ =>
                seleniumText(find(id("logged-in-user"))) should matchText(
                  userPartyId.toProtoPrimitive
                ),
            )
            actAndCheck(
              "The user logs out", {
                eventuallyClickOn(id("logout-button"))
              },
            )(
              "The user sees the login screen again",
              _ => find(id("oidc-login-button")) should not be empty,
            )
        }
    }

    "redirect to the previous page after login" taggedAs LocalAuth0Test in { implicit env =>
      withAuth0LoginCheck("randomUser", 3000, onboardThroughWalletUI = true) {
        (auth0User, userPartyId, wd) =>
          implicit val webDriver: WebDriverType = wd

          clue("The user taps 100 amulets") {
            tapAmulets(100)
          }

          actAndCheck(
            "The user logs out", {
              eventuallyClickOn(id("logout-button"))
            },
          )(
            "The user sees the login screen again",
            _ => find(id("login-button")) should not be None,
          )

          val paymentRequestContractId = clue("A payment is created") {
            val (paymentRequestContractId, _) = createPaymentRequest(
              aliceValidatorBackend.participantClientWithAdminToken,
              auth0User.id,
              userPartyId,
              Seq(
                receiverAmount(userPartyId, BigDecimal("1.5"), Unit.AMULETUNIT)
              ),
            )
            paymentRequestContractId
          }

          clue("User has to login again") {
            go to s"http://localhost:3000/confirm-payment/${paymentRequestContractId.contractId}"
            loginViaAuth0InCurrentPage(
              auth0User.email,
              auth0User.password,
              () => find(id("confirm-payment")) should not be None,
            )
          }
      }
    }
  }
}
