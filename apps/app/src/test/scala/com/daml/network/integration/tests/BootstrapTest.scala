package com.daml.network.integration.tests

import better.files.File
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner

class BootstrapTest extends CoinIntegrationTest with HasConsoleScriptRunner {

  "Bootstrap script should pass" in { implicit env =>
    runScript(File("apps/splitwise/frontend/bootstrap.sc"))(env.environment)
  }
}
