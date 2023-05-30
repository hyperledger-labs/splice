package com.daml.network.integration.tests.runbook

import com.daml.network.LiveDevNetTest
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

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

  "The Web UIs of SVs 1-4 are reachable and working as expected" taggedAs LiveDevNetTest in { env =>
    // we put many checks in one test case to reduce testing time (logging in is slow)
    for (i <- (1 to 4)) {
      val svUiUrl = s"https://sv.sv-$i.svc.${sys.env("NETWORK_APPS_ADDRESS")}/";
      // hardcoded to save on four environment variables; we don't expect this to change often
      val svUsername = s"admin@sv$i.com";
      // our current practice is to use the same password for all SVs
      val svPassword = sys.env(s"SV_WEB_UI_PASSWORD");
      val sv = env.svs.remote.find(sv => sv.name == s"sv$i").value

      withFrontEnd("sv") { implicit webDriver =>
        clue(s"We can log in to SV$i's UI") {
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
              inside(findAll(className("value-name")).toSeq.take(2)) {
                case Seq(svUser, svPartyId) =>
                  svUser.text should matchText(svInfo.svUser)
                  svPartyId.text should matchText(svInfo.svParty.toProtoPrimitive)
              }
            },
          )
        }

        clue("SVs 1-4 have placed a coin price vote") {
          actAndCheck(
            "Opening coin price tab",
            click on "navlink-cc-price",
          )(
            s"We see that SV$i and the other SVs have placed a coin price vote",
            _ => {
              // the price fields hold "Not Set" if the SV has never voted
              val priceR = """^\s*(\d+(\.\d+)?)\s*USD\s*$""".r
              clue(s"We see that SV$i has voted") {
                inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
                  e.text should fullyMatch regex priceR
                }
              }
              clue(s"We see, via SV$i's UI, that all others of SV1-4 have voted") {
                val votes = findAll(className("coin-price-table-row"))
                  .map(row =>
                    (PartyId
                      .tryFromProtoPrimitive(
                        row
                          .childElement(className("sv-party"))
                          .text
                      ) -> row.childElement(className("coin-price")).text)
                  )
                  .toMap
                env.svs.remote
                  .filter(_ != sv)
                  .foreach(sv_ => votes(sv_.getSvcInfo().svParty) should fullyMatch regex priceR)
              }
            },
          )
        }

        clue(s"We can create a validator onboarding secret via SV$i's UI") {
          val (_, oldSecrets) = actAndCheck(
            "Opening validator onboarding tab",
            click on "navlink-validator-onboarding",
          )(
            s"Creating an onboarding secret",
            _ => {
              waitForQuery(id("create-validator-onboarding-secret"))
              findAll(className("onboarding-secret-table-secret")).toSeq.map(e => e.text)
            },
          )
          actAndCheck(
            "click",
            click on "create-validator-onboarding-secret",
          )(
            s"We see that SV$i has created an onboarding secret",
            _ => {
              val secrets =
                findAll(className("onboarding-secret-table-secret")).toSeq.map(e => e.text)
              secrets.map(row => row should have size 44)
              secrets.diff(oldSecrets) should not be empty
            },
          )
        }

        clue(s"We can log out of SV$i's UI") {
          click on "logout-button"
          waitForQuery(id("oidc-login-button"))
        }
      }
    }
  }
}
