package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class CoinRulesUpgradesIntegrationTest extends CNNodeIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs((_, c) =>
          c.copy(enableCoinRulesUpgrade = true)
        )(config)
      )
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms.updateScanAppConfig(_.copy(enableCoinRulesUpgrade = true))(config)
      )

  "Scan exposes both coinRules versions correctly" in { implicit env =>
    scan.getCoinRules()
    scan.getCoinRulesV1Test()
  }
}
