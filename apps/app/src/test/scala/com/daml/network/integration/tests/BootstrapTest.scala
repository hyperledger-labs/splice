package com.daml.network.integration.tests

import better.files.File
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class BootstrapTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences
    with HasConsoleScriptRunner {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  "Bootstrap script should pass" in { implicit env =>
    runScript(File("apps/splitwise/frontend/bootstrap.canton"))(env.environment)
  }
}
