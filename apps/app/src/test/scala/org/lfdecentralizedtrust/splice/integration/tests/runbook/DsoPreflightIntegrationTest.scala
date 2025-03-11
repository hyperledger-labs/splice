package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.duration.DurationInt

/** Preflight test that makes sure that *our* SVs (1-4) have initialized fine.
  */
class DsoPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv", "docs")
    with PreflightIntegrationTestUtil
    with SvUiPreflightIntegrationTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "SVs 1-4 are online and reachable via their public HTTP API" in { implicit env =>
    env.svs.remote.foreach(sv =>
      clue(s"Checking SV at ${sv.httpClientConfig.url}") {
        eventuallySucceeds(timeUntilSuccess = 2.minutes) {
          sv.getDsoInfo()
        }
      }
    )
  }

  "The Web UIs of SVs 1-4 are reachable and working as expected" in { env =>
    // we put many checks in one test case to reduce testing time (logging in is slow)
    for (i <- (1 to 4)) {
      val ingressName = if (i == 1) "sv-2" else s"sv-$i-eng"
      val svUiUrl = s"https://sv.${ingressName}.${sys.env("NETWORK_APPS_ADDRESS")}/";
      // hardcoded to save on four environment variables; we don't expect this to change often
      val svUsername = s"admin@sv$i-dev.com";
      // our current practice is to use the same password for all SVs
      val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD")
      val sv = env.svs.remote.find(sv => sv.name == s"sv$i").value
      val svInfo = eventuallySucceeds() { sv.getDsoInfo() }

      val votedSvParties =
        env.svs.remote.filter(_ != sv).map(sv_ => eventuallySucceeds() { sv_.getDsoInfo().svParty })

      withFrontEnd("sv") { implicit webDriver =>
        testSvUi(
          svUiUrl,
          svUsername,
          svPassword,
          Some(svInfo),
          votedSvParties,
        )
      }
    }
  }

  "The docs are reachable and working" in { _ =>
    val docsUrl = s"https://${sys.env("NETWORK_APPS_ADDRESS")}/";
    withFrontEnd("docs") { implicit webDriver =>
      silentActAndCheck(
        "load docs",
        go to docsUrl,
      )(
        "The docs are live",
        { _ =>
          find(id("global-synchronizer-for-the-canton-network")) should not be empty
        },
      )
    }
  }
}
