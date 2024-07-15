package com.daml.network.integration.tests

import com.daml.network.config.ConfigTransforms
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SvFrontendNoDevNetIntegrationTest extends SvFrontendCommonIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.noDevNet(config))
      // disable top-ups since in non-devnet setups, validators need to pay for top-ups
      .withTrafficTopupsDisabled

}
