package com.daml.network.integration.tests.runbook

import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.daml.network.integration.tests.FrontendTestCommon
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.SvcInfo
import com.digitalasset.canton.topology.PartyId

trait SvUiIntegrationTestUtil extends CNNodeTestCommon {

  this: FrontendTestCommon =>

  def testSvUi(
      svUiUrl: String,
      svUsername: String,
      svPassword: String,
      svInfo: Option[SvcInfo],
      votedSvParties: Seq[PartyId],
      extraChecks: => Unit = (),
  )(implicit webDriver: WebDriverType) = {

    clue(s"We can log in to the SV UI") {
      actAndCheck(
        s"Logging in to SV UI at: ${svUiUrl}", {
          completeAuth0LoginWithAuthorization(
            svUiUrl,
            svUsername,
            svPassword,
            () => find(id("logout-button")) should not be empty,
          )
        },
      )(
        s"We see a table with correct info data about the SV",
        _ => {
          svInfo.foreach(si =>
            inside(findAll(className("general-svc-value-name")).toSeq.take(2)) {
              case Seq(svUser, svPartyId) =>
                seleniumText(svUser) should matchText(si.svUser)
                seleniumText(svPartyId) should matchText(si.svParty.toProtoPrimitive)
            }
          )
        },
      )

      clue("SVs 1-4 have placed a coin price vote") {
        actAndCheck(
          "Opening coin price tab",
          click on "navlink-cc-price",
        )(
          s"We see that this SV and the other SVs have placed a coin price vote",
          _ => {
            // the price fields hold "Not Set" if the SV has never voted
            val priceR = """^\s*(\d+(\.\d+)?)\s*USD\s*$""".r
            clue(s"We see that this SV has voted") {
              inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
                e.text should fullyMatch regex priceR
              }
            }
            clue(s"We see, via this SV's UI, that all others of SV1-4 have voted") {
              val votes = findAll(className("coin-price-table-row"))
                .map(row =>
                  (PartyId
                    .tryFromProtoPrimitive(
                      seleniumText(row.childElement(className("sv-party")))
                    ) -> row.childElement(className("coin-price")).text)
                )
                .toMap
              votedSvParties.foreach(sv => votes(sv) should fullyMatch regex priceR)
            }
          },
        )
      }

      clue(s"We can create a validator onboarding secret via this SV's UI") {
        dumpDebugInfoOnFailure {
          val (_, oldFirstSecret) = actAndCheck(
            "Opening validator onboarding tab",
            click on "navlink-validator-onboarding",
          )(
            s"Creating an onboarding secret",
            _ => {
              waitForQuery(id("create-validator-onboarding-secret"))
              waitForQuery(className("onboarding-secret-table"))
              val secretsItr = findAll(className("onboarding-secret-table-secret"))
              if (secretsItr.hasNext) Some(secretsItr.next().text) else None
            },
          )
          actAndCheck(
            "click",
            click on "create-validator-onboarding-secret",
          )(
            s"We see that this SV has created an onboarding secret",
            _ => {
              val secretsItr = findAll(className("onboarding-secret-table-secret"))
              val firstSecret = if (secretsItr.hasNext) Some(secretsItr.next().text) else None
              firstSecret should not be oldFirstSecret
              inside(firstSecret) { case Some(s) =>
                s should have size 44
              }
            },
          )
        }
      }

      extraChecks

      clue(s"We can log out of this SV's UI") {
        click on "logout-button"
        waitForQuery(id("oidc-login-button"))
      }
    }

  }

}
