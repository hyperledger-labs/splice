package org.lfdecentralizedtrust.splice.integration.tests.reonboard

import cats.syntax.parallel.*
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.runbook.{
  SvUiPreflightIntegrationTestUtil,
  PreflightIntegrationTestUtil,
}
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  SvFrontendTestUtil,
  WalletFrontendTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.util.FutureInstances.*
import org.openqa.selenium.support.ui.Select
import org.openqa.selenium.{By, Keys}
import org.scalatest.time.{Minutes, Span}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SvOffboardPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv1", "sv2", "sv3", "sv4", "sv")
    with SvUiPreflightIntegrationTestUtil
    with SvFrontendTestUtil
    with PreflightIntegrationTestUtil
    with FrontendLoginUtil
    with WalletFrontendTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(5, Minutes)))

  private val svRunbookName = "DA-Helm-Test-Node"
  private val walletUrl = s"https://wallet.sv.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private val svUsername = s"admin@sv-dev.com"
  private val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");

  "The SV can log in to their wallet and tap" in { implicit env =>
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

      // 10020 is for the
      tapAmulets(100020)
    }
  }

  "SV runbook is offboarded" in { implicit env =>
    import env.executionContext

    val requestReasonUrl = "This is a request reason url."
    val requestReasonBody = "This is a request reason."

    val sv1 = env.svs.remote.find(sv => sv.name == s"sv1").value

    val newVoteRequestCid = clue(s"sv1 create vote request") {
      withWebUiSv(1) { implicit webDriver =>
        click on "navlink-votes"
        val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
        dropDownAction.selectByValue("SRARC_OffboardSv")

        val dropDownSv = new Select(webDriver.findElement(By.id("display-members")))
        dropDownSv.selectByVisibleText(svRunbookName)

        click on "tab-panel-in-progress"
        val previousVoteRequestsInProgress = find(id("sv-voting-in-progress-table-body"))
          .map(_.findAllChildElements(className("vote-row-tracking-id")).toSeq.size)
          .getOrElse(0)

        val now = Instant.now()

        inside(find(id("create-reason-summary"))) { case Some(element) =>
          element.underlying.sendKeys(requestReasonBody)
        }
        inside(find(id("create-reason-url"))) { case Some(element) =>
          element.underlying.sendKeys(requestReasonUrl)
        }

        setDateTime(
          "sv1",
          "datetime-picker-vote-request-expiration",
          DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneOffset.UTC)
            .format(
              now.plusSeconds(60)
            ),
        )

        clue("wait for the submit button to become clickable") {
          eventually(5.seconds)(
            find(id("create-voterequest-submit-button")).value.isEnabled shouldBe true
          )
        }
        clue("click the submit button") {
          click on "create-voterequest-submit-button"
          click on "vote-confirmation-dialog-accept-button"
        }

        eventually() {
          val voteRequestsInProgress = find(id("sv-voting-in-progress-table-body")).toList
            .flatMap(_.findAllChildElements(className("vote-row-tracking-id")).toSeq)
          voteRequestsInProgress.size shouldBe (previousVoteRequestsInProgress + 1)
          voteRequestsInProgress.head.text
        }
      }
    }

    clue(s"other svs cast vote on request") {
      Seq(2, 3, 4).parTraverse { svIndex =>
        Future {
          withWebUiSv(svIndex) { implicit webDriver =>
            click on "navlink-votes"
            val tbody = find(id("sv-voting-action-needed-table-body"))
            val reviewButton = eventually() {
              inside(tbody) { case Some(tb) =>
                val rows = tb.findAllChildElements(className("MuiDataGrid-row")).toSeq
                val row = rows.find { row =>
                  row
                    .findAllChildElements(className("vote-row-tracking-id"))
                    .toSeq
                    .headOption
                    .exists(
                      _.text == newVoteRequestCid
                    )
                }.value
                row.findAllChildElements(className("vote-row-action")).toSeq.headOption.value
              }
            }
            actAndCheck(
              "sv operator can review the vote request", {
                reviewButton.underlying.click()
              },
            )(
              "sv operator can see the new vote request detail",
              _ => {
                inside(find(id("vote-request-modal-action-name"))) { case Some(element) =>
                  element.text should matchText("SRARC_OffboardSv")
                }
              },
            )

            val voteReasonBody = "vote reason body"
            val voteReasonUrl = "vote reason url"
            actAndCheck(
              "sv operator can cast vote", {
                click on "cast-vote-button"
                click on "accept-vote-button"
                inside(find(id("vote-reason-url"))) { case Some(element) =>
                  element.underlying.sendKeys(voteReasonUrl)
                }
                inside(find(id("vote-reason-body"))) { case Some(element) =>
                  element.underlying.sendKeys(voteReasonBody)
                }
                click on "save-vote-button"
                click on "vote-confirmation-dialog-accept-button"
              },
            )(
              "sv can see the new vote request detail",
              _ => {
                inside(find(id("vote-request-modal-vote-reason-body"))) { case Some(element) =>
                  element.text should matchText(voteReasonBody)
                }
                inside(find(id("vote-request-modal-vote-reason-url"))) { case Some(element) =>
                  element.text should matchText(voteReasonUrl)
                }
              },
            )
            webDriver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE)
          }
        }
      }.futureValue
    }

    clue("SV should be offboarded") {
      // 240 to account for
      // - 60s expiration time of the vote request
      // - 60s buffer because we only set minutes not seconds
      // - 30s polling interval for the trigger to kick in
      // - general slowness
      eventuallySucceeds(timeUntilSuccess = 240.seconds) {
        sv1
          .getDsoInfo()
          .dsoRules
          .payload
          .svs
          .asScala
          .filter(_._2.name == svRunbookName) shouldBe empty
      }
    }
  }
}
