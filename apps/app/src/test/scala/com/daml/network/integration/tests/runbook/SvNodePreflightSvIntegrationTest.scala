package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.daml.network.util.{DirectoryFrontendTestUtil, FrontendLoginUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.DomainId

import scala.concurrent.duration.*
import scala.util.Random

abstract class SvNodePreflightSvIntegrationTestBase
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

  protected def svUsername: String
  protected def isDevNet: Boolean
  protected val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");

  "The SV UI of the node is working as expected" in { _ =>
    val svUiUrl = s"https://sv.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");
    withFrontEnd("sv") { implicit webDriver =>
      testSvUi(svUiUrl, svUsername, svPassword, None, Seq(), isDevNet)
    }
  }

  "CometBFT is working" in { _ =>
    val svUiUrl = s"https://sv.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
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
            if (isDevNet) {
              forAll(Range(1, 5)) { _ =>
                e.text should include(s"\"moniker\": \"Canton-Foundation-1\"")
              }
            } else {
              forAll(Range(1, 2)) { _ =>
                e.text should include(s"\"moniker\": \"Canton-Foundation\"")
              }
            }
          }
        },
      )
    }
  }

  "The SV can log in to their wallet" in { _ =>
    val walletUrl = s"https://wallet.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/"

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
      if (isDevNet) { // can't tap in NonDevNet
        tapCoins(100)
      }
    }
  }

  val scanUrl = s"https://scan.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}"
  "The Scan UI is working" in { _ =>
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
      if (isDevNet) { // SV missing CC in NonDevNet
        reserveDirectoryNameFor(
          () => login(),
          cnsName,
          "1.0000000000",
          "USD",
          "90 days",
        )
        clue(s"Reserved directory name is shown in scan ui at ${scanUrl}") {
          go to scanUrl
          eventually(3.minutes) {
            val cnsEntryNames =
              findAll(className("directory-entry")).map(elm => seleniumText(elm)).toList.distinct
            cnsEntryNames.exists(_.startsWith(cnsName)) should be(true)
          }
        }
      }
    }
  }

  "Key API endpoints are reachable and functional" in { implicit env =>
    val auth0 = auth0UtilFromEnvVars("https://canton-network-sv-test.us.auth0.com", Some("sv"))
    val token = eventuallySucceeds() {
      getAuth0ClientCredential(
        "bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn",
        "https://validator.example.com/api",
        auth0,
      )(noTracingLogger)
    }
    val svValidatorClient = vc("svTestValidator").copy(token = Some(token))
    val svScanClient = scancl("svTestScan")
    val sv1ScanClient = scancl("sv1Scan")
    val participantId = clue("Can dump participant identities from SV validator") {
      svValidatorClient.dumpParticipantIdentities().id
    }
    val activeDomain = clue("Can get active domain from Scan") {
      val svActiveDomain = DomainId.tryFromString(
        svScanClient.getCoinConfigAsOf(env.environment.clock.now).globalDomain.activeDomain
      )
      val sv1ActiveDomain = DomainId.tryFromString(
        sv1ScanClient.getCoinConfigAsOf(env.environment.clock.now).globalDomain.activeDomain
      )
      svActiveDomain shouldBe sv1ActiveDomain
      svActiveDomain
    }
    clue("Can get member traffic status from Scan") {
      val svTrafficStatus = svScanClient.getMemberTrafficStatus(activeDomain, participantId.member)
      val sv1TrafficStatus =
        sv1ScanClient.getMemberTrafficStatus(activeDomain, participantId.member)
      svTrafficStatus shouldBe sv1TrafficStatus
    }
  }
}

final class SvNodePreflightSvIntegrationTest extends SvNodePreflightSvIntegrationTestBase {
  override protected def svUsername = s"admin@sv-dev.com";
  override protected def isDevNet = true
}

final class SvNodePreflightSvNonDevNetIntegrationTest extends SvNodePreflightSvIntegrationTestBase {
  override protected def svUsername = s"admin@sv.com";
  override protected def isDevNet = false

  "ParticipantId matches the expected one" in { _ =>
    val svUiUrl = s"https://sv.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
    // we need to update this hard-coded string when we change it
    val participantId = "122094f834829f4e48f0712fca8fec13ac867e97da47311729508704f7f59994c7b5"

    withFrontEnd("sv") { implicit webDriver =>
      clue(s"Logging in to sv ui at ${svUiUrl}") {
        completeAuth0LoginWithAuthorization(
          svUiUrl,
          svUsername,
          svPassword,
          () => find(id("logout-button")) should not be empty,
        )
      }

      clue("The suffix matches the participantID") {
        eventually() {
          val valueCells = findAll(className("general-svc-value-name")).toSeq
          forAtLeast(1, valueCells)(cell => seleniumText(cell) should include(participantId))
        }
      }
    }
  }
}
