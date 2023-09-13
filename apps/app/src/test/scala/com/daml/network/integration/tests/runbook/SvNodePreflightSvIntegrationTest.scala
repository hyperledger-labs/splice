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
    with PreflightIntegrationTestUtil
    with SvUiIntegrationTestUtil
    with FrontendLoginUtil
    with WalletFrontendTestUtil
    with DirectoryFrontendTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "The SV UI of the node is working as expected" in { _ =>
    val svUiUrl = s"https://sv.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val svUsername = s"admin@sv-dev.com";
    val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");
    withFrontEnd("sv") { implicit webDriver =>
      testSvUi(svUiUrl, svUsername, svPassword, None, Seq())
    }
  }

  "CometBFT is working" in { _ =>
    val svUiUrl = s"https://sv.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val svUsername = s"admin@sv-dev.com";
    val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");

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
    val svUsername = s"admin@sv-dev.com";
    val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");

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
    val svUsername = s"admin@sv-dev.com";
    val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");
    val cnsName = s"da-test-${Random.alphanumeric.take(10).mkString.toLowerCase}.unverified.cns"

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

  "Dumping participant identities works" in { implicit env =>
    val auth0 = auth0UtilFromEnvVars("https://canton-network-sv-test.us.auth0.com", Some("sv"))

    val token = getAuth0ClientCredential(
      "bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn",
      "https://validator.example.com/api",
      auth0,
    )

    val svValidatorClient = vc("svTestValidator").copy(token = Some(token))
    svValidatorClient.dumpParticipantIdentities()
  }
}
