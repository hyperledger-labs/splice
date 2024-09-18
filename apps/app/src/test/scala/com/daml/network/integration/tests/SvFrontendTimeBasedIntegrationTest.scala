package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.{FrontendLoginUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.DomainId
import org.openqa.selenium.WebDriver
import org.slf4j.event.Level

import java.time.Duration as JavaDuration

class SvFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTest("sv1", "sv2")
    with FrontendLoginUtil
    with WalletTestUtil
    with TimeTestUtil {

  private val dummyDsoDomainId = DomainId.tryFromString("domain1::domain")

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(
        this.getClass.getSimpleName
      )

  def assertRowContentsMatch(key: String, value: String)(implicit webDriver: WebDriver): Unit = {
    val queryResult = find(id(key))
    queryResult should not be empty
    inside(queryResult) {
      case Some(queryRow) => {
        queryRow.childElement(className("general-dso-key-name")).text should matchText(key)
        seleniumText(
          queryRow.childElement(className("general-dso-value-name"))
        ) should matchText(value)
      }
    }
  }

  def assertRowContentsDiffer(key: String, value: String)(implicit webDriver: WebDriver): Unit = {
    val queryResult = find(id(key))
    queryResult should not be empty
    inside(queryResult) {
      case Some(queryRow) => {
        queryRow.childElement(className("general-dso-key-name")).text should matchText(
          key
        )
        seleniumText(
          queryRow.childElement(className("general-dso-value-name"))
        ) shouldNot matchText(value)
      }
    }
  }

  "SV UIs" should {
    // TODO(#7649): enable test back if automatic delegate election is re-enabled in new flow
    "see election results reflected in the UI" ignore { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "log in to sv1 UI", {
            login(sv1UIPort, sv1Backend.config.ledgerApiUser)
          },
        )(
          "We see the expected delegate and epoch",
          _ => {
            click on "information-tab-general"
            assertRowContentsMatch(
              "dsoLeaderPartyId",
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
            )
            assertRowContentsMatch("dsoEpoch", "0")
          },
        )

        webDriver.quit()
      }

      clue("stop the delegate sv1 long enough for an election to occur") {
        val automationConfig = sv2Backend.config.automation
        val effectiveTimeoutPlusBuffer = SvUtil
          .fromRelTime(SvUtil.defaultDsoRulesConfig(dummyDsoDomainId).dsoDelegateInactiveTimeout)
          .plus(automationConfig.pollingInterval.asJava)
          .plus(JavaDuration.ofSeconds(5))
        sv1Backend.stop()
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          {
            advanceTime(tickDurationWithBuffer)
            advanceTime(effectiveTimeoutPlusBuffer)
          },
          entries => {
            forExactly(3, entries) { line =>
              line.message should include(
                "Noticed an DsoRules epoch change"
              )
            }
          },
        )
      }

      withFrontEnd("sv2") { implicit webDriver =>
        actAndCheck(
          "log in to sv2 UI", {
            login(sv2UIPort, sv2Backend.config.ledgerApiUser)
          },
        )(
          "We see a new delegate and epoch",
          _ => {
            click on "information-tab-general"
            assertRowContentsDiffer(
              "dsoLeaderPartyId",
              sv2Backend.getDsoInfo().svParty.toProtoPrimitive,
            )
            assertRowContentsMatch("dsoEpoch", "1")
          },
        )
      }
    }
  }
}
