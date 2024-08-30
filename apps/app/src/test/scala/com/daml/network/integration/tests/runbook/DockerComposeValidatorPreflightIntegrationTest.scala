package com.daml.network.integration.tests.runbook

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.FrontendIntegrationTest
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.duration.*
import scala.sys.process.*

class DockerComposeValidatorPreflightIntegrationTest
    extends FrontendIntegrationTest("alice-selfhosted")
    with FrontendLoginUtil
    with WalletFrontendTestUtil {
  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(this.getClass.getSimpleName)

  "docker-compose based validator works against the deployed cluster" in { implicit env =>
    // Assumes a docker network `onvpn` exists, and is connected to the VPN
    val ret = Seq("scripts/compose-validator-for-tests.sh", "-d", "-n", "onvpn").!
    if (ret != 0) {
      fail("Start script failed")
    }
    try {
      withFrontEnd("alice-selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        actAndCheck(5.minute)(
          "Login as administrator",
          login(80, "administrator", "wallet.localhost"),
        )(
          "administrator is already onboarded",
          _ => seleniumText(find(id("logged-in-user"))) should startWith("da-composeValidator-1"),
        )
        tapAmulets(123.4)
      }
    } finally {
      "cluster/deployment/compose/stop.sh" !
    }
  }
}
