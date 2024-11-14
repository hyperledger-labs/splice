package org.lfdecentralizedtrust.splice.integration.tests.upgrades

import org.lfdecentralizedtrust.splice.environment.{EnvironmentImpl, DarResources}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.runbook.SvUiIntegrationTestUtil
import org.lfdecentralizedtrust.splice.util.SvFrontendTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.Select

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.collection.parallel.CollectionConverters.*

/** This test should run after a cluster has been upgraded, and will vote for new daml versions.
  * Note that it needs to run on the commit of the upgrade, so that DarResources.*_current points to the latest version.
  */
class DamlCIUpgradeVotePreflightTest
    extends FrontendIntegrationTestWithSharedEnvironment(
      "sv1",
      "sv2",
      "sv3",
      "sv4",
      "sv",
    )
    with SvUiIntegrationTestUtil
    with SvFrontendTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  "DamlClusterUpgradeTest" should {

    "vote to upgrade daml" in { _ =>
      val amulet_current = DarResources.amulet_current.metadata.version
      val dsoGovernance_current = DarResources.dsoGovernance_current.metadata.version
      val amuletNameService_current = DarResources.amuletNameService_current.metadata.version
      val wallet_current = DarResources.wallet_current.metadata.version
      val walletPayments_current = DarResources.walletPayments_current.metadata.version
      val validatorLifecycle_current = DarResources.validatorLifecycle_current.metadata.version

      val now = Instant.now()

      clue(s"sv1 create vote request") {
        withWebUiSv(1) { implicit webDriver =>
          click on "navlink-votes"
          val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
          dropDownAction.selectByValue("CRARC_AddFutureAmuletConfigSchedule")

          // 20m to be effective so as to give enough time to upgrade the SV and Validator runbooks.
          // The expiration doesn't matter so as long as it's enough for SVs to vote, but it needs to be less than the effective date.
          Seq(
            "datetime-picker-amulet-configuration" -> 20L,
            "datetime-picker-vote-request-expiration" -> 5L,
          ).foreach { case (picker, minutes) =>
            setDateTime(
              "sv1",
              picker,
              DateTimeFormatter
                .ofPattern("MM/dd/yyyy hh:mm a")
                .withZone(ZoneOffset.UTC)
                .format(now.plusSeconds(60L * minutes)),
            )
          }

          inside(find(id("create-reason-summary"))) { case Some(element) =>
            element.underlying.sendKeys("Upgrading DARs to latest version.")
          }
          inside(find(id("create-reason-url"))) { case Some(element) =>
            element.underlying.sendKeys("https://example.com")
          }

          Seq(
            "amulet" -> amulet_current,
            "amuletNameService" -> amuletNameService_current,
            "dsoGovernance" -> dsoGovernance_current,
            "validatorLifecycle" -> validatorLifecycle_current,
            "wallet" -> wallet_current,
            "walletPayments" -> walletPayments_current,
          ).foreach { case (inputSuffix, version) =>
            val input = find(id(s"packageConfig.$inputSuffix-value")).value.underlying
            input.clear()
            input.sendKeys(version.toString)
          }

          clue("sv1 creates the vote request") {
            clickVoteRequestSubmitButtonOnceEnabled()
          }
        }
      }

      clue("Other svs vote in favor") {
        val svsF = Seq(2, 3, 4).map(withWebUiSv[Unit]) :+ withWebUiSvRunbook[Unit]
        svsF.par.foreach { svF =>
          svF { implicit webDriver =>
            click on "navlink-votes"
            click on "tab-panel-action-needed"
            click on className("vote-row-action")
            click on "cast-vote-button"
            click on "accept-vote-button"
            click on "save-vote-button"
            try {
              click on "vote-confirmation-dialog-accept-button"
            } catch {
              case _: org.openqa.selenium.NoSuchElementException =>
              // Maybe we're on an old version without a confirmation dialog
              // TODO(#16090) remove once the ciupgade base version contains #15693
            }

          }
        }
      }

      clue("The vote passes because all SVs accepted it") {
        withWebUiSv(1) { implicit webDriver =>
          click on "navlink-votes"
          click on "tab-panel-planned"

          val tbody = find(id("sv-vote-results-planned-table-body"))
          inside(tbody) { case Some(tb) =>
            val rows = tb.findAllChildElements(className("vote-row-action")).toSeq
            rows.size shouldBe 1
          }
        }
      }
    }

  }

}
