package com.daml.network.integration.tests.runbook

import com.daml.network.LiveDevNetTest
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Preflight test that makes sure that *our* SVs (1-4) have initialized fine.
  */
class SvcPreflightIntegrationTest extends FrontendIntegrationTestWithSharedEnvironment("sv") {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "SVs 1-4 are online and reachable via their public HTTP API" taggedAs LiveDevNetTest in {
    implicit env =>
      env.svs.remote.foreach(sv =>
        clue(s"Checking SV at ${sv.httpClientConfig.url}") {
          sv.getSvcInfo()
        }
      )
  }

  "We can login to the Web UIs of SVs 1-4" taggedAs LiveDevNetTest in { env =>
    for (i <- (1 to 4)) {
      val svUiUrl = s"https://sv.sv-$i.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
      // hardcoded to save on four environment variables; we don't expect this to change often
      val svUsername = s"admin@sv$i.com";
      // our current practice is to use the same password for all SVs
      val svPassword = sys.env(s"SV_WEB_UI_PASSWORD");
      val sv = env.svs.remote.find(sv => sv.name == s"sv$i").value

      withFrontEnd("sv") { implicit webDriver =>
        actAndCheck(
          s"Logging in to SV$i's UI at: ${svUiUrl}", {
            completeAuth0LoginWithAuthorization(
              svUiUrl,
              svUsername,
              svPassword,
              () => find(id("logout-button")) should not be empty,
            )
          },
        )(
          s"We see a table with correct info data about SV$i",
          _ => {
            val svInfo = sv.getSvcInfo()
            inside(findAll(className("value-name")).toSeq.take(2)) { case Seq(svUser, svPartyId) =>
              svUser.text should matchText(svInfo.svUser)
              svPartyId.text should matchText(svInfo.svParty.toProtoPrimitive)
            }
          },
        )
        clue(s"Logging out of SV$i's UI") {
          click on "logout-button"
          waitForQuery(id("oidc-login-button"))
        }
      }
    }
  }
}
