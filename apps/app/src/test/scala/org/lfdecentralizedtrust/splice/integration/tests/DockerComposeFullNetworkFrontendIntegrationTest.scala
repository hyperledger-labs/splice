package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, WalletFrontendTestUtil}

import scala.concurrent.duration.*
import scala.sys.process.*

class DockerComposeFullNetworkFrontendIntegrationTest
    extends FrontendIntegrationTest("frontend")
    with FrontendLoginUtil
    with WalletFrontendTestUtil {
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.empty(this.getClass.getSimpleName)

  // This does nothing as the wallet clients will not actually be connected to the compose setup
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override lazy val resetRequiredTopologyState = false

  val partyHint = "local-composeValidator-1"

  "docker-compose based SV and validator work" in { implicit env =>
    try {
      val ret = Seq("build-tools/splice-compose.sh", "start_network", "-w").!
      if (ret != 0) {
        fail("Failed to start docker-compose SV and validator")
      }

      clue("Test validator") {
        withFrontEnd("frontend") { implicit webDriver =>
          eventuallySucceeds()(go to "http://wallet.localhost")
          actAndCheck(timeUntilSuccess = 60.seconds)(
            "Login as administrator",
            login(80, "administrator", "wallet.localhost"),
          )(
            "administrator is already onboarded",
            _ => seleniumText(find(id("logged-in-user"))) should startWith(partyHint),
          )
          // Wait for some traffic to be bought before proceeding, so that we don't
          // hit a "traffic below reserved amount" error
          waitForTrafficPurchase()
          go to "http://wallet.localhost"
          actAndCheck(
            "Login as alice",
            loginOnCurrentPage(80, "alice", "wallet.localhost"),
          )(
            "Alice can onboard",
            _ => find(id("onboard-button")).value.text should not be empty,
          )
          actAndCheck(
            "onboard alice",
            eventuallyClickOn(id("onboard-button")),
          )(
            "Alice is logged in",
            _ => seleniumText(find(id("logged-in-user"))) should not be "",
          )
          tapAmulets(345.6)
        }

        clue("Basic test of SV UIs") {
          withFrontEnd("frontend") { implicit webDriver =>
            actAndCheck(
              "Open the Scan UI",
              go to "scan.localhost:8080",
            )(
              "Open rounds should be listed",
              _ => findAll(className("open-mining-round-row")) should have length 2,
            )

            actAndCheck(timeUntilSuccess = 60.seconds)(
              "Login to the wallet as administrator",
              login(8080, "administrator", "wallet.localhost"),
            )(
              "administrator is already onboarded",
              _ => seleniumText(find(id("logged-in-user"))) should startWith("sv.sv.ans"),
            )

            actAndCheck()(
              "Login to the SV app as administrator",
              login(8080, "administrator", "sv.localhost"),
            )(
              "administrator is already onboarded, and the app is working",
              _ => {
                seleniumText(
                  find(id("svUser")).value
                    .childElement(className("general-dso-value-name"))
                ) should be("administrator")
              },
            )

          }
        }
      }
    } finally {
      Seq("build-tools/splice-compose.sh", "stop_network", "-D", "-f").!
    }
  }

}
