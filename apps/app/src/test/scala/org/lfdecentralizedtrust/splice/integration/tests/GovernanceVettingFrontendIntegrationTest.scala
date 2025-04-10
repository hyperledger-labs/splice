package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  PackageConfig,
  USD,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, SvFrontendTestUtil, SvTestUtil}
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.Select

import java.nio.file.Path
import java.time.{LocalDateTime, ZoneOffset}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.CollectionHasAsScala

// TODO(#16139): remove this test once the old frontend has been retired
class GovernanceVettingFrontendIntegrationTest
    extends SvFrontendCommonIntegrationTest
    with SvTestUtil
    with SvFrontendTestUtil
    with FrontendLoginUtil
    with VotesFrontendTestUtil
    with ValidatorLicensesFrontendTestUtil {

  private val initialPackageConfig = InitialPackageConfig(
    amuletVersion = "0.1.7",
    amuletNameServiceVersion = "0.1.7",
    dsoGovernanceVersion = "0.1.10",
    validatorLifecycleVersion = "0.1.1",
    walletVersion = "0.1.7",
    walletPaymentsVersion = "0.1.7",
  )

  private val splitwellDarPath = "daml/dars/splitwell-0.1.7.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialPackageConfig = initialPackageConfig)
        )(config)
      )
      .addConfigTransform((_, conf) =>
        ConfigTransforms.updateAllValidatorConfigs((name, validatorConfig) =>
          if (name == "splitwellValidator")
            validatorConfig.copy(
              appInstances = validatorConfig.appInstances.updated(
                "splitwell",
                validatorConfig
                  .appInstances("splitwell")
                  .copy(
                    dars = validatorConfig.appInstances("splitwell").dars ++ Seq(
                      Path.of(splitwellDarPath)
                    )
                  ),
              )
            )
          else
            validatorConfig
        )(conf)
      )

  "Super Validators" should {
    "use the new governance voting logic after vetting the dars" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "sv1 operator can login and browse to the governance tab", {
            go to s"http://localhost:$sv1UIPort/votes"
            loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
          },
        )(
          "sv1 can see the create vote request button",
          _ => {
            find(id("create-voterequest-submit-button")) should not be empty
            find(id("display-actions")) should not be empty
            val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
            val options = dropDownAction.getOptions.asScala.map(_.getText)
            Seq(
              "Add DSO App Configuration Schedule",
              "Remove DSO App Configuration Schedule",
              "Update DSO App Configuration Schedule",
            ).foreach(action => {
              options should contain(action)
            })
            options should not contain "Set Amulet Rules Configuration"
            find(id("checkbox-set-effective-at-threshold")) shouldBe empty
            find(id("tab-panel-planned")) should not be empty
          },
        )
        clue("The old governance voting logic is still in place") {
          Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach { backend =>
            backend
              .getDsoInfo()
              .amuletRules
              .payload
              .configSchedule
              .initialValue
              .packageConfig shouldBe new PackageConfig(
              "0.1.7",
              "0.1.7",
              "0.1.10",
              "0.1.1",
              "0.1.7",
              "0.1.7",
            )
          }
        }
        val (_, requestTrackingCid) = actAndCheck(
          "sv1 creates a request to vet the dars to the new voting logic", {
            val amuletConfig: AmuletConfig[USD] =
              sv1ScanBackend.getAmuletRules().payload.configSchedule.initialValue
            val newAmuletConfig =
              new AmuletConfig(
                amuletConfig.transferConfig,
                amuletConfig.issuanceCurve,
                amuletConfig.decentralizedSynchronizer,
                amuletConfig.tickDuration,
                new PackageConfig("0.1.8", "0.1.8", "0.1.11", "0.1.2", "0.1.8", "0.1.8"),
                java.util.Optional.empty(),
              )
            val scheduledTime = LocalDateTime
              .ofInstant(CantonTimestamp.now().toInstant, ZoneOffset.UTC)
              .plusMinutes(1)
              .toInstant(ZoneOffset.UTC)
            val configChangeAction = new ARC_AmuletRules(
              new CRARC_AddFutureAmuletConfigSchedule(
                new AmuletRules_AddFutureAmuletConfigSchedule(
                  new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
                    scheduledTime,
                    newAmuletConfig,
                  )
                )
              )
            )
            sv1Backend.createVoteRequest(
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              configChangeAction,
              "url",
              "description",
              sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
              None,
            )
          },
        )(
          "sv1 finds the new vote request",
          _ => {
            sv1Backend.listVoteRequests().loneElement.contractId.contractId
          },
        )
        actAndCheck(timeUntilSuccess = 1.minutes)(
          "all other svs accept the request, which triggers the vote request early closing logic", {
            vote(sv2Backend, requestTrackingCid, isAccept = true, "2", finalVote = true)
            vote(sv3Backend, requestTrackingCid, isAccept = true, "3", finalVote = true)
            vote(sv4Backend, requestTrackingCid, isAccept = true, "4", finalVote = true)
          },
        )(
          "The new dars have been vetted",
          _ => {
            Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach { backend =>
              backend
                .getDsoInfo()
                .amuletRules
                .payload
                .configSchedule
                .initialValue
                .packageConfig shouldBe new PackageConfig(
                "0.1.8",
                "0.1.8",
                "0.1.11",
                "0.1.2",
                "0.1.8",
                "0.1.8",
              )
            }
          },
        )
        clue("sv1 sees the new action `CRARC_SetConfig` in the dropdown menu and new UI features") {
          eventually() {
            find(id("display-actions")) should not be empty
            val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
            val options = dropDownAction.getOptions.asScala.map(_.getText)
            Seq(
              "Add DSO App Configuration Schedule",
              "Remove DSO App Configuration Schedule",
              "Update DSO App Configuration Schedule",
            ).foreach(action => {
              options should not contain action
            })
            options should contain("Set Amulet Rules Configuration")
            find(id("checkbox-set-effective-at-threshold")) should not be empty
            find(id("tab-panel-planned")) shouldBe empty
          }
        }
      }
    }
  }

}
