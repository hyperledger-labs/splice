package com.daml.network.integration.tests

import better.files.File
import com.daml.network.config.CNNodeConfigTransforms.useSelfSignedTokensForLedgerApiAuth
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

class DFBootstrapTest extends CNNodeIntegrationTest with HasConsoleScriptRunner {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      // we want a network in the same state that we would get when running `start-backends-for-local-frontend-testing.sh`
      .fromResources(
        Seq("minimal-topology.conf"),
        this.getClass.getSimpleName,
      )
      .clearConfigTransforms()
      .withTrafficTopupsDisabled // TODO(#7204)
      .addConfigTransforms((_, config) => useSelfSignedTokensForLedgerApiAuth("test")(config))

  "Bootstrap script should pass" in { implicit env =>
    // the script logs errors when a CNS name check fails but then recovers from this
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
        forAll(lines) { line => line.message should (include("No directory entry found for name")) }
      },
    )
  }
}
