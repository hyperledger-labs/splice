package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition

class SvFrontendNoDevNetIntegrationTest extends SvFrontendCommonIntegrationTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.noDevNet(config))
      // disable top-ups since in non-devnet setups, validators need to pay for top-ups
      .withTrafficTopupsDisabled

}
