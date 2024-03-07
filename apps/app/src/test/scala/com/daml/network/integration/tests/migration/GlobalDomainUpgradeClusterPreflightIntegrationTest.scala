package com.daml.network.integration.tests.migration

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.daml.network.integration.tests.runbook.SvUiIntegrationTestUtil
import com.daml.network.util.SvFrontendTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.openqa.selenium.support.ui.Select
import org.openqa.selenium.{By, Keys}

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

class GlobalDomainUpgradeClusterPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv")
    with SvUiIntegrationTestUtil
    with SvFrontendTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  "Global domain upgrade is scheduled" in { implicit env =>
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
          now.plusSeconds(150).truncatedTo(ChronoUnit.SECONDS)
        )
        inside(find(id("nextScheduledDomainUpgrade.time-value"))) { case Some(element) =>
          element.underlying.clear()
          element.underlying.sendKeys(scheduledUpgradeTime)
        }

        // We hard-coded 1 here for now. It should be more robust to calculated from the migration id of node currently deployed.
        inside(find(id("nextScheduledDomainUpgrade.migrationId-value"))) { case Some(element) =>
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
              now.plusSeconds(120)
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

    clue(s"other sv case vote on request") {
      for (i <- 2 to 4) {
        withWebUiSv(i) { implicit webDriver =>
          click on "navlink-votes"
          val tbody = find(id("sv-voting-action-needed-table-body"))
          val reviewButtonOpt = inside(tbody) { case Some(tb) =>
            val rows = tb.findAllChildElements(className("MuiDataGrid-row")).toSeq
            val row = rows.find { row =>
              row
                .findAllChildElements(className("vote-row-tracking-id"))
                .toSeq
                .headOption
                .exists(
                  _.text == newVoteRequestCid
                )
            }
            row.flatMap(_.findAllChildElements(className("vote-row-action")).toSeq.headOption)
          }
          reviewButtonOpt match {
            case None => () // domain upgrade is already scheduled
            case Some(reviewButton) =>
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
                "sv2 can see the new vote request detail",
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
      }
    }

    clue("Domain migration should be scheduled") {
      // 300 to account for
      // - 120s expiration time of the vote request
      // - 60s buffer because we only set minutes not seconds
      // - 30s polling interval for the trigger to kick in
      // - general slowness
      eventuallySucceeds(timeUntilSuccess = 300.seconds) {
        val nextScheduledDomainUpgrade = sv1
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .nextScheduledDomainUpgrade
          .toScala
        nextScheduledDomainUpgrade.value.migrationId shouldBe 1L
      }
    }
  }
}
