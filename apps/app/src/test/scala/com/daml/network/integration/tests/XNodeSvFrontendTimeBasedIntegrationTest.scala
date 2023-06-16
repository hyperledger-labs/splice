package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.{FrontendLoginUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import org.openqa.selenium.WebDriver

import java.time.Duration as JavaDuration

class XNodeSvFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTest("sv1", "sv2")
    with FrontendLoginUtil
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.simpleTopologyXCentralizedDomainWithSimTime(
      this.getClass.getSimpleName
    )

  def assertRowContentsMatch(key: String, value: String)(implicit webDriver: WebDriver): Unit = {
    val queryResult = find(id(key))
    queryResult should not be empty
    inside(queryResult) {
      case Some(queryRow) => {
        queryRow.childElement(className("general-svc-key-name")).text should matchText(
          key
        )
        queryRow.childElement(className("general-svc-value-name")).text should matchText(value)
      }
    }
  }

  def assertRowContentsDiffer(key: String, value: String)(implicit webDriver: WebDriver): Unit = {
    val queryResult = find(id(key))
    queryResult should not be empty
    inside(queryResult) {
      case Some(queryRow) => {
        queryRow.childElement(className("general-svc-key-name")).text should matchText(
          key
        )
        queryRow.childElement(className("general-svc-value-name")).text shouldNot matchText(value)
      }
    }
  }

  "SV UIs" should {
    val sv1Port = 3010
    val sv2Port = 3012

    "see election results reflected in the UI" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "log in to sv1 UI", {
            login(sv1Port, sv1.config.ledgerApiUser)
          },
        )(
          "We see the expected leader and epoch",
          _ => {
            click on "information-tab-general"
            assertRowContentsMatch("svcLeaderPartyId", sv1.getSvcInfo().svParty.toProtoPrimitive)
            assertRowContentsMatch("svcEpoch", "0")
          },
        )

        webDriver.quit()
      }

      clue("stop the leader sv1 long enough for an election to occur") {
        val automationConfig = sv2.config.automation
        val effectiveTimeoutPlusBuffer = SvUtil
          .fromRelTime(SvUtil.defaultSvcRulesConfig().leaderInactiveTimeout)
          .plus(automationConfig.pollingInterval.asJava)
          .plus(JavaDuration.ofSeconds(5))
        sv1.stop()
        advanceTime(tickDurationWithBuffer)
        advanceTime(effectiveTimeoutPlusBuffer)
      }

      withFrontEnd("sv2") { implicit webDriver =>
        actAndCheck(
          "log in to sv2 UI", {
            login(sv2Port, sv2.config.ledgerApiUser)
          },
        )(
          "We see a new leader and epoch",
          _ => {
            click on "information-tab-general"
            assertRowContentsDiffer("svcLeaderPartyId", sv2.getSvcInfo().svParty.toProtoPrimitive)
            assertRowContentsMatch("svcEpoch", "1")
          },
        )
      }
    }
  }
}
