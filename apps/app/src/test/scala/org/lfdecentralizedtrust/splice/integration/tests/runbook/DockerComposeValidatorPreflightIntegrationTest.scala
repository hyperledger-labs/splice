package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTest
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, WalletFrontendTestUtil}

import scala.concurrent.duration.*
import scala.sys.process.*

class DockerComposeValidatorPreflightIntegrationTest
    extends FrontendIntegrationTest("alice-selfhosted")
    with FrontendLoginUtil
    with WalletFrontendTestUtil {
  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.preflightTopology(this.getClass.getSimpleName)

  "docker-compose based validator works against the deployed cluster" in { implicit env =>
    // Assumes a docker network `onvpn` exists, and is connected to the VPN
    val partyHint = "da-composeValidator-1"
    val ret =
      Seq("build-tools/splice-compose.sh", "start", "-d", "-n", "onvpn", "-w", "-p", partyHint).!
    if (ret != 0) {
      fail("Failed to start docker-compose validator")
    }
    try {
      withFrontEnd("alice-selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        actAndCheck(timeUntilSuccess = 60.seconds)(
          "Login as administrator",
          login(80, "administrator", "wallet.localhost"),
        )(
          "administrator is already onboarded",
          _ => seleniumText(find(id("logged-in-user"))) should startWith(partyHint),
        )
        tapAmulets(123.4)
      }
    } finally {
      Seq("build-tools/splice-compose.sh", "stop", "-D", "-f") !
    }
  }
}
