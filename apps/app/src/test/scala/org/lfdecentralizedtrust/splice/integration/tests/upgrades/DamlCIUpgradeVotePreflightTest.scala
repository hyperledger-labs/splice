package org.lfdecentralizedtrust.splice.integration.tests.upgrades

import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTest
import org.lfdecentralizedtrust.splice.integration.tests.runbook.SvUiPreflightIntegrationTestUtil
import org.openqa.selenium.By

import scala.collection.parallel.CollectionConverters.*
import scala.jdk.CollectionConverters.*

/** This test should run after a cluster has been upgraded, and will vote for new daml versions.
  * Note that it needs to run on the commit of the upgrade, so that DarResources.*_current points to the latest version.
  */
class DamlCIUpgradeVotePreflightTest
    extends FrontendIntegrationTest(
      "sv1",
      "sv2",
      "sv3",
      "svda1",
      "sv",
    )
    with SvUiPreflightIntegrationTestUtil {

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

      val formPrefix = "set-amulet-config-rules"

      clue(s"sv1 create vote request") {
        withWebUiSv("sv1") { implicit webDriver =>
          eventuallyClickOn(id("navlink-governance"))

          eventuallyClickOn(id("initiate-proposal-button"))

          clue("select action and click next") {
            eventually() {
              val actionDropdown = webDriver.findElement(By.id("select-action"))
              actionDropdown.click()
            }
            eventually() {
              val actionOption =
                webDriver.findElement(By.cssSelector("[data-testid='CRARC_SetConfig']"))
              actionOption.click()
            }
            eventually() {
              click on id("next-button")
            }
          }

          eventually() {
            find(id(s"$formPrefix-summary")) should not be empty
          }

          Seq(
            "packageConfigAmulet" -> amulet_current,
            "packageConfigAmuletNameService" -> amuletNameService_current,
            "packageConfigDsoGovernance" -> dsoGovernance_current,
            "packageConfigValidatorLifecycle" -> validatorLifecycle_current,
            "packageConfigWallet" -> wallet_current,
            "packageConfigWalletPayments" -> walletPayments_current,
          ).foreach { case (fieldName, version) =>
            eventually() {
              val input =
                webDriver.findElement(By.cssSelector(s"[data-testid='config-field-$fieldName']"))
              input.clear()
              input.sendKeys(version.toString)
            }
          }

          eventually() {
            inside(find(id(s"$formPrefix-summary"))) { case Some(element) =>
              element.underlying.sendKeys("Upgrading DARs to latest version.")
            }
          }
          eventually() {
            inside(find(id(s"$formPrefix-url"))) { case Some(element) =>
              element.underlying.sendKeys("https://example.com")
            }
          }

          clue("sv1 creates the vote request") {
            eventually() {
              val submitButton = webDriver.findElement(By.id("submit-button"))
              submitButton.click()
            }
            eventually() {
              val submitButton = webDriver.findElement(By.id("submit-button"))
              submitButton.getText shouldBe "Submit Proposal"
            }
            eventually() {
              val submitButton = webDriver.findElement(By.id("submit-button"))
              submitButton.click()
            }
          }
        }
      }

      clue("Other svs vote in favor") {
        val svsF = Seq("sv2", "sv3", "svda1").map(withWebUiSv[Unit]) :+ withWebUiSvRunbook[Unit]
        svsF.par.foreach { svF =>
          svF { implicit webDriver =>
            eventuallyClickOn(id("navlink-governance"))

            eventuallySucceeds() {
              find(
                cssSelector("[data-testid='action-required-section']")
              ) should not be empty withClue "'Action Required' section"
              val actionsRequired =
                webDriver.findElements(
                  By.cssSelector("[data-testid='action-required-view-details']")
                )
              actionsRequired.size should be > 0
              actionsRequired.asScala.head.click()
            }

            eventuallySucceeds() {
              find(
                cssSelector("[data-testid='your-vote-reason-input']")
              ) should not be empty withClue "vote 'Reason' textfield"
            }
            click on cssSelector("[data-testid='your-vote-accept']")

            clue("wait for the vote submission success message") {
              eventuallySucceeds() {
                inside(find(cssSelector("[data-testid='vote-submission-success']"))) {
                  case Some(element) =>
                    element.text shouldBe "Vote successfully updated!"
                }
              }
            }
          }
        }
      }

      clue("The request is displayed in the inflight votes section") {
        withWebUiSv("sv1") { implicit webDriver =>
          eventuallyClickOn(id("navlink-governance"))

          eventually() {
            val proposals =
              webDriver.findElements(By.cssSelector("[data-testid='inflight-proposals-row']"))
            proposals.size shouldBe 1
          }
        }
      }
    }

  }

}
