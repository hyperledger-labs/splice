package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File
import com.digitalasset.canton.ConsoleScriptRunner
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.useSelfSignedTokensForLedgerApiAuth
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class BootstrapTest extends IntegrationTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
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
          ConsoleScriptRunner.run(
            env.environment,
            File("apps/splitwell/frontend/bootstrap-minimal.sc").toJava,
            logger,
          )
        }
        clue("And it should pass a second time...") {
          ConsoleScriptRunner.run(
            env.environment,
            File("apps/splitwell/frontend/bootstrap-minimal.sc").toJava,
            logger,
          )
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
