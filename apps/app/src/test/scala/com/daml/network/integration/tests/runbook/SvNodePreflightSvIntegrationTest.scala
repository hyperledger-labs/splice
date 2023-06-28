package com.daml.network.integration.tests.runbook

import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.util.{DirectoryFrontendTestUtil, FrontendLoginUtil, WalletFrontendTestUtil}

import scala.concurrent.duration.*
import scala.util.Random

class SvNodePreflightSvIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv")
    with SvUiIntegrationTestUtil
    with FrontendLoginUtil
    with WalletFrontendTestUtil
    with DirectoryFrontendTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "The SV UI of the node is working as expected" in { env =>
    val svUiUrl = s"https://sv.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val svUsername = s"admin@sv.com";
    val svPassword = sys.env(s"SV_WEB_UI_PASSWORD");
    val votedSvParties = env.svs.remote.map(_.getSvcInfo().svParty)
    withFrontEnd("sv") { implicit webDriver =>
      testSvUi(svUiUrl, svUsername, svPassword, None, votedSvParties)
    }
  }

  "CometBFT is working" in { _ =>
    val svUiUrl = s"https://sv.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val svUsername = s"admin@sv.com";
    val svPassword = sys.env(s"SV_WEB_UI_PASSWORD");

    withFrontEnd("sv") { implicit webDriver =>
      actAndCheck(
        s"Logging in to SV UI at: ${svUiUrl}", {
          completeAuth0LoginWithAuthorization(
            svUiUrl,
            svUsername,
            svPassword,
            () => find(id("logout-button")) should not be empty,
          )

          click on "information-tab-cometBft-debug"
        },
      )(
        s"We see all other SVs as peers",
        _ => {
          inside(find(id("comet-bft-debug-network"))) { case Some(e) =>
            forAll(Range(1, 5)) { sv =>
              e.text should include(s"\"moniker\": \"Canton-Foundation-$sv\"")
            }
          }
        },
      )
    }
  }

  "The SV can log in to their wallet" in { _ =>
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
      tapCoins(100)
    }
  }

  "The Scan UI is working" in { _ =>
    val scanUrl = s"https://scan.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}"

    withFrontEnd("sv") { implicit webDriver =>
      go to scanUrl
      eventually(3.minutes) {
        val asOfRound = find(id("as-of-round")).value.text
        asOfRound should startWith("The content on this page is computed as of round: ")
        asOfRound should not be "The content on this page is computed as of round: --"
      }
    }
  }

  "The Directory UI is working" in { _ =>
    val directoryUrl = s"https://directory.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}"
    val svUsername = s"admin@sv.com";
    val svPassword = sys.env(s"SV_WEB_UI_PASSWORD");
    val cnsName = s"da-test-${Random.alphanumeric.take(10).mkString}.cns"

    withFrontEnd("sv") { implicit webDriver =>
      def login(): Unit = {
        actAndCheck(
          s"Logging in to directory at $directoryUrl", {
            completeAuth0LoginWithAuthorization(
              directoryUrl,
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
      reserveDirectoryNameFor(
        () => login(),
        cnsName,
        "1.0",
        "USD",
        "90 days",
      )
    }
  }

  // TODO(#5979): Revise this after adding auth...
  "Dumping participant identities works" in { implicit env =>
    def svValidatorClient(implicit env: CNNodeTestConsoleEnvironment) = vc("svTestValidator")
    svValidatorClient.dumpParticipantIdentities()
  }
}
