package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.cometbft.CometBftContainerAround
import com.daml.network.sv.config.CometBftConfig
import com.daml.network.util.SvTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

class SvCometBftIntegrationTest
    extends CNNodeIntegrationTest
    with SvTestUtil
    with CometBftContainerAround {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform(CNNodeConfigTransforms.onlySv1)
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs_(config =>
          config
            .focus(_.cometBftConfig)
            .replace(
              Some(
                CometBftConfig(
                  enabled = true,
                  connectionUri = connectionConfig.uri,
                )
              )
            )
        )(config)
      )
      .withManualStart

  "report cometBft status" in { implicit env =>
    initSvcWithSv1Only()
    eventually() {
      sv1.cometBftNodeStatus().catchingUp shouldBe false
    }
  }

  "get the debug dump for cometBft" in { implicit env =>
    initSvcWithSv1Only()
    eventually() {
      sv1.cometBftNodeDump().abciInfo.isObject shouldBe true
    }
  }
}
