package org.lfdecentralizedtrust.splice.integration.tests.upgrades

import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.runbook.SvUiPreflightIntegrationTestUtil
import org.lfdecentralizedtrust.splice.util.SvFrontendTestUtil
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
      "svda1",
      "sv",
    )
    with SvUiPreflightIntegrationTestUtil
    with SvFrontendTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
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
        withWebUiSv("sv1") { implicit webDriver =>
          eventuallyClickOn(id("navlink-votes"))
          val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
          dropDownAction.selectByValue("CRARC_SetConfig")

          eventuallyClickOn(id("action-change-dialog-proceed"))

          // 20m to be effective so as to give enough time to upgrade the SV and Validator runbooks.
          // The expiration doesn't matter so as long as it's enough for SVs to vote, but it needs to be less than the effective date.
          Seq(
            "datetime-picker-vote-request-effectivity" -> 20L,
            "datetime-picker-vote-request-expiration" -> 5L,
          ).foreach { case (picker, minutes) =>
            setDateTime(
              "sv1",
              picker,
              DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
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
        val svsF = Seq("sv2", "sv3", "svda1").map(withWebUiSv[Unit]) :+ withWebUiSvRunbook[Unit]
        svsF.par.foreach { svF =>
          svF { implicit webDriver =>
            eventuallySucceeds() {
              eventuallyClickOn(id("navlink-votes"))
              eventuallyClickOn(id("tab-panel-action-needed"))
              eventuallyClickOn(className("vote-row-action"))
            }
            eventuallyClickOn(id("cast-vote-button"))
            eventuallyClickOn(id("accept-vote-button"))
            eventuallyClickOn(id("save-vote-button"))
            eventuallyClickOn(id("vote-confirmation-dialog-accept-button"))
          }
        }
      }

      clue("The request is displayed in the in progress section") {
        withWebUiSv("sv1") { implicit webDriver =>
          eventuallyClickOn(id("navlink-votes"))
          eventuallyClickOn(id("tab-panel-in-progress"))

          val tbody = find(id("sv-voting-in-progress-table-body"))
          inside(tbody) { case Some(tb) =>
            eventually() {
              val rows = tb.findAllChildElements(className("vote-row-action")).toSeq
              rows.size shouldBe 1
            }
          }
        }
      }
    }

  }

}
