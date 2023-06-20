package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Preflight test that makes sure that *our* SVs (1-4) have initialized fine.
  */
class SvcPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv")
    with SvUiIntegrationTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "SVs 1-4 are online and reachable via their public HTTP API" in { implicit env =>
    env.svs.remote.foreach(sv =>
      clue(s"Checking SV at ${sv.httpClientConfig.url}") {
        sv.getSvcInfo()
      }
    )
  }

  "The Web UIs of SVs 1-4 are reachable and working as expected" in { env =>
    // we put many checks in one test case to reduce testing time (logging in is slow)
    for (i <- (1 to 4)) {
      val svUiUrl = s"https://sv.sv-$i.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
      // hardcoded to save on four environment variables; we don't expect this to change often
      val svUsername = s"admin@sv$i.com";
      // our current practice is to use the same password for all SVs
      val svPassword = sys.env(s"SV_WEB_UI_PASSWORD")
      val sv = env.svs.remote.find(sv => sv.name == s"sv$i").value
      val svInfo = sv.getSvcInfo()

      val votedSvParties = env.svs.remote.filter(_ != sv).map(sv_ => sv_.getSvcInfo().svParty)

      withFrontEnd("sv") { implicit webDriver =>
        testSvUi(
          svUiUrl,
          svUsername,
          svPassword,
          Some(svInfo),
          votedSvParties,
          // TODO(#5954) Move this into testSvUi once external SVs also have their own sequencer/mediator.
          {
            actAndCheck("Go to general information tab", click on "navlink-svc")(
              "button for domain status appears",
              _ => find(id("information-tab-canton-domain-status")) should not be empty,
            )
            actAndCheck(
              "Click on domain status tab",
              click on "information-tab-canton-domain-status",
            )(
              "Observe sequencer and mediator as active",
              _ => {
                val activeCells = findAll(className("active-value")).toSeq
                activeCells should have length 2
                forAll(activeCells)(_.text shouldBe "true")
              },
            )
            ()
          },
        )
      }
    }
  }
}
