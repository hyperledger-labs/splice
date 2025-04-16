package org.lfdecentralizedtrust.splice.integration.tests.reonboard

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.runbook.PreflightIntegrationTestUtil
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, WalletFrontendTestUtil}
import org.scalatest.time.{Minutes, Span}

class PrepareSvReonboardPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv")
    with PreflightIntegrationTestUtil
    with FrontendLoginUtil
    with WalletFrontendTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(5, Minutes)))

  private val walletUrl = s"https://wallet.sv.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private val svUsername = s"admin@sv-dev.com"
  private val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");

  "The SV can log in to their wallet and tap" in { implicit env =>
    withFrontEnd("sv") { implicit webDriver =>
      actAndCheck(
        s"Logging in to wallet at ${walletUrl}", {
          completeAuth0LoginWithAuthorization(
            walletUrl,
            svUsername,
            svPassword,
            () => find(id("logout-button")) should not be empty,
          )
        },
      )(
        "User is logged in and onboarded",
        _ => {
          userIsLoggedIn()
        },
      )

      // In SvReOnboardPreflightIntegrationTest, we are transferring 100000 USD
      // from a temporary validator that is configured to use the offboarded SV party
      // to the reonboarded SV.
      tapAmulets(100020)
    }
  }
}
