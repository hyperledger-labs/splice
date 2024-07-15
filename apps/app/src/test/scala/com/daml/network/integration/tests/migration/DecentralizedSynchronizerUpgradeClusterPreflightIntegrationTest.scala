package com.daml.network.integration.tests.migration

import cats.syntax.parallel.*
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.daml.network.integration.tests.runbook.SvUiIntegrationTestUtil
import com.daml.network.util.SvFrontendTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.util.FutureInstances.*
import org.openqa.selenium.support.ui.Select
import org.openqa.selenium.{By, Keys}
import org.scalatest.time.{Minute, Span}

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

class DecentralizedSynchronizerUpgradeClusterPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv1", "sv2", "sv3", "sv4")
    with SvUiIntegrationTestUtil
    with SvFrontendTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  "Global domain upgrade is scheduled" in { implicit env =>
    import env.executionContext

    // This triggers the pausing of the domain and the creation of dumps later on

    val requestReasonUrl = "This is a request reason url."
    val requestReasonBody = "This is a request reason."

    val sv1 = env.svs.remote.find(sv => sv.name == s"sv1").value

    val newVoteRequestCid = clue(s"sv1 create vote request") {
      withWebUiSv(1) { implicit webDriver =>
        click on "navlink-votes"
        val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
        dropDownAction.selectByValue("SRARC_SetConfig")

        click on "tab-panel-in-progress"
        val previousVoteRequestsInProgress = find(id("sv-voting-in-progress-table-body"))
          .map(_.findAllChildElements(className("vote-row-tracking-id")).toSeq.size)
          .getOrElse(0)

        click on "enable-next-scheduled-domain-upgrade"

        val now = Instant.now()
        val scheduledUpgradeTime = DateTimeFormatter.ISO_INSTANT.format(
          now.plusSeconds(80).truncatedTo(ChronoUnit.SECONDS)
        )
        inside(find(id("nextScheduledSynchronizerUpgrade.time-value"))) { case Some(element) =>
          element.underlying.clear()
          element.underlying.sendKeys(scheduledUpgradeTime)
        }

        // We hard-coded 1 here for now. It should be more robust to calculated from the migration id of node currently deployed.
        inside(find(id("nextScheduledSynchronizerUpgrade.migrationId-value"))) {
          case Some(element) =>
            element.underlying.clear()
            element.underlying.sendKeys("1")
        }

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
            .ofPattern("MM/dd/yyyy hh:mm a")
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
                  element.text should matchText("SRARC_SetConfig")
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

    clue("Domain migration should be scheduled") {
      // 240 to account for
      // - 60s expiration time of the vote request
      // - 60s buffer because we only set minutes not seconds
      // - 30s polling interval for the trigger to kick in
      // - general slowness
      eventuallySucceeds(timeUntilSuccess = 240.seconds) {
        val nextScheduledSynchronizerUpgrade = sv1
          .getDsoInfo()
          .dsoRules
          .payload
          .config
          .nextScheduledSynchronizerUpgrade
          .toScala
        nextScheduledSynchronizerUpgrade.value.migrationId shouldBe 1L
      }
    }
  }
}
