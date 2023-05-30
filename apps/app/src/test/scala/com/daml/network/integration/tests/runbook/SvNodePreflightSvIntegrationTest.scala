package com.daml.network.integration.tests.runbook

import com.daml.network.LiveDevNetTest
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.util.FrontendLoginUtil

class SvNodePreflightSvIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv")
    with SvUiIntegrationTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "The SV UI of the node is working as expected" taggedAs LiveDevNetTest in { _ =>
    val svUiUrl = s"https://sv.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val svUsername = s"admin@sv.com";
    val svPassword = sys.env(s"SV_WEB_UI_PASSWORD");
    // TODO(#5166): Consider getting the party IDs of the other members from the UI,
    // and using those for the coin voting check in testSvUi below
    val votedSvParties = Seq.empty

    withFrontEnd("sv") { implicit webDriver =>
      testSvUi(svUiUrl, svUsername, svPassword, None, votedSvParties)
    }
  }

  "The SV can log in to their wallet" taggedAs LiveDevNetTest in { _ =>
    val walletUrl = s"https://wallet.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/"
    val svUsername = s"admin@sv.com";
    val svPassword = sys.env(s"SV_WEB_UI_PASSWORD");

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
    }
  }

}
