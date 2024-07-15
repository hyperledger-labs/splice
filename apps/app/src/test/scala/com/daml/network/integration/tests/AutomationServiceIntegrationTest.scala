package com.daml.network.integration.tests

import com.digitalasset.canton.logging.SuppressionRule
import com.daml.network.config.ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.splitwell.automation.SplitwellInstallRequestTrigger
import com.daml.network.sv.automation.SvDsoAutomationService
import org.slf4j.event.Level

import java.util.regex.Pattern

class AutomationServiceIntegrationTest extends SvIntegrationTestBase {
  import AutomationServiceIntegrationTest.*
  override def environmentDefinition =
    super.environmentDefinition.addConfigTransforms((_, config) =>
      updateAutomationConfig(ConfigurableApp.Sv)(
        // deliberately choosing a mismatched trigger to pause
        _.withPausedTrigger[SomeNonSvTrigger]
      )(config)
    )

  "initialization warns on invalid paused-trigger setting" in { implicit env =>
    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.WARN))(
      initDsoWithSv1Only() withClue "spin up dso",
      _.filter(
        _.message.matches(
          s"paused-triggers specified but not present.*"
            + Pattern.quote(classOf[SvDsoAutomationService].getSimpleName)
            + ".*"
            + Pattern.quote(SomeNonSvTrigger.getSimpleName)
        )
      ) should have size 1,
    )
  }
}

object AutomationServiceIntegrationTest {
  // which trigger doesn't really matter, as long as it doesn't match the app
  // we're updating the config for in the test above
  type SomeNonSvTrigger = SplitwellInstallRequestTrigger
  val SomeNonSvTrigger = classOf[SplitwellInstallRequestTrigger]
}
