package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.HttpSvAppClient.DsoInfo
import com.digitalasset.canton.topology.PartyId
import scala.concurrent.duration.*

trait SvUiPreflightIntegrationTestUtil extends TestCommon {

  this: FrontendTestCommon =>

  protected val coreSvIngressNames = Map(
    "sv1" -> "sv-2",
    "sv2" -> "sv-2-eng",
    "sv3" -> "sv-3-eng",
    "svda1" -> "sv-1",
  );

  def testSvUi(
      svUiUrl: String,
      svUsername: String,
      svPassword: String,
      svInfo: Option[DsoInfo],
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
            inside(findAll(className("general-dso-value-name")).toSeq.take(2)) {
              case Seq(svUser, svPartyId) =>
                seleniumText(svUser) should matchText(si.svUser)
                seleniumText(svPartyId) should matchText(si.svParty.toProtoPrimitive)
            }
          )
        },
      )

      actAndCheck("Go to general information tab", click on "navlink-dso")(
        "button for domain status appears",
        _ => find(id("information-tab-canton-domain-status")) should not be empty,
      )

      actAndCheck(
        "Click on domain status tab",
        click on "information-tab-canton-domain-status",
      )(
        "Observe sequencer and mediator as active",
        _ => {
          val activeCells = findAll(className("active-value")).toSeq
          activeCells should have length 2
          forAll(activeCells)(_.text shouldBe "true")
        },
      )

      clue("SVs 1-3 + DA-1 have placed a amulet price vote") {
        actAndCheck(
          "Opening amulet price tab",
          click on "navlink-amulet-price",
        )(
          s"We see that this SV and the other SVs have placed a amulet price vote",
          _ => {
            // the price fields hold "Not Set" if the SV has never voted
            val priceR = """^\s*(\d+(\.\d+)?)\s*USD\s*$""".r
            clue(s"We see that this SV has voted") {
              inside(find(id("cur-sv-amulet-price-usd"))) { case Some(e) =>
                e.text should fullyMatch regex priceR
              }
            }
            clue(s"We see, via this SV's UI, that all others of SV1-3 + DA-1 have voted") {
              val votes = findAll(className("amulet-price-table-row"))
                .map(row =>
                  (PartyId
                    .tryFromProtoPrimitive(
                      seleniumText(row.childElement(className("sv-party")))
                    ) -> row.childElement(className("amulet-price")).text)
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
              waitForQuery(id("create-party-hint"))
              waitForQuery(id("create-validator-onboarding-secret"))
              waitForQuery(className("onboarding-secret-table"))
              val secretsItr = findAll(className("onboarding-secret-table-secret"))
              if (secretsItr.hasNext) Some(secretsItr.next().text) else None
            },
          )
          actAndCheck(timeUntilSuccess = 2.minutes)(
            "fill party hint and generate new secret", {
              inside(find(id("create-party-hint"))) { case Some(element) =>
                element.underlying.sendKeys("splice-client-1")
              }
              clickCreateValidatorOnboardingSecretButtonOnceEnabled()
            },
          )(
            s"We see that this SV has created an onboarding secret",
            _ => {
              val secretsItr = findAll(className("onboarding-secret-table-secret"))
              val firstSecret = if (secretsItr.hasNext) Some(secretsItr.next().text) else None
              firstSecret should not be oldFirstSecret
              inside(firstSecret) { case Some(s) =>
                s should not be ""
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

  def withWebUiSv[A](svName: String)(f: WebDriverType => A): A = {
    val ingressName = coreSvIngressNames.get(svName).value
    val svUiUrl = s"https://sv.${ingressName}.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val svUsername = s"admin@${svName}-dev.com";
    val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD")

    withWebUiSv(svName, svUiUrl, svUsername, svPassword)(f)
  }

  def withWebUiSvRunbook[A](f: WebDriverType => A): A = {
    val svUiUrl = s"https://sv.sv.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val svUsername = s"admin@sv-dev.com";
    val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD")
    withWebUiSv("sv", svUiUrl, svUsername, svPassword)(f)
  }

  private def withWebUiSv[A](
      svFrontend: String,
      svUiUrl: String,
      svUsername: String,
      svPassword: String,
  )(f: WebDriverType => A): A = {
    withFrontEnd(svFrontend) { implicit webDriver =>
      clue(s"Logging in to SV UI at: ${svUiUrl}") {
        completeAuth0LoginWithAuthorization(
          svUiUrl,
          svUsername,
          svPassword,
          () => find(id("logout-button")) should not be empty,
        )
      }
      f(webDriver)
    }

  }
}
