package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.useSelfSignedTokensForLedgerApiAuth
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

class BootstrapTest extends IntegrationTest with HasConsoleScriptRunner {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      // we want a network in the same state that we would get when running `start-backends-for-local-frontend-testing.sh`
      .fromResources(
        Seq("minimal-topology.conf"),
        this.getClass.getSimpleName,
      )
      .clearConfigTransforms()
      .addConfigTransforms((_, config) =>
        ConfigTransforms.withPausedSvDomainComponentsOffboardingTriggers()(config)
      )
      .addConfigTransform((_, config) => useSelfSignedTokensForLedgerApiAuth("test")(config))
      // We reduce the polling interval here primarily for the top-up trigger to ensure that a top-up happens as soon as
      // possible during the validator setup and other txs do not get throttled for want of traffic.
      .addConfigTransform((_, config) => ConfigTransforms.reducePollingInterval(config))

  "Bootstrap script should pass" in { implicit env =>
    // the script logs errors when a ANS name check fails but then recovers from this
    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        clue("It should pass one time...") {
          runScript(File("apps/splitwell/frontend/bootstrap-minimal.sc"))(env.environment)
        }
        clue("And it should pass a second time...") {
          runScript(File("apps/splitwell/frontend/bootstrap-minimal.sc"))(env.environment)
        }
      },
      lines => {
        forAll(lines) { line =>
          line.message should (include("Entry with name")
            .and(include("not found")))
            .or(include("Encountered 4 consecutive transient failures"))
        }
      },
    )
  }
}
