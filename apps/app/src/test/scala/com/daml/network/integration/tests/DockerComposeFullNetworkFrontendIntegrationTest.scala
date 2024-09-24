package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.sys.process.*

class DockerComposeFullNetworkFrontendIntegrationTest
    extends FrontendIntegrationTest("frontend")
    with FrontendLoginUtil
    with WalletFrontendTestUtil {
  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.empty(this.getClass.getSimpleName)

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
          actAndCheck()(
            "Login as administrator",
            login(80, "administrator", "wallet.localhost"),
          )(
            "administrator is already onboarded",
            _ => {
              seleniumText(find(id("logged-in-user"))) should startWith(partyHint)
              // Wait for some traffic to be bought before proceeding, so that we don't
              // hit a "traffic below reserved amount" error
              val txs = findAll(className("tx-row")).toSeq
              val trafficPurchases = txs.filter { txRow =>
                txRow.childElement(className("tx-action")).text.contains("Sent") &&
                txRow.childElement(className("tx-subtype")).text.contains("Extra Traffic Purchase")
              }
              trafficPurchases should not be empty
            },
          )
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
            click on "onboard-button",
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

            actAndCheck()(
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
