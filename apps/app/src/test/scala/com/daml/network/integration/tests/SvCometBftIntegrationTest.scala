package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.CometBftNetworkPlugin
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.config.CometBftConfig
import com.daml.network.util.SvTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.NamedLoggerFactory
import monocle.macros.syntax.lens.*

import scala.concurrent.duration.*

class SvCometBftIntegrationTest extends CNNodeIntegrationTest with SvTestUtil {

  registerPlugin(new CometBftNetworkPlugin("sv_cometbft_integration_test", NamedLoggerFactory.root))

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs_ { config =>
          config
            .focus(_.cometBftConfig)
            .replace(
              Some(
                CometBftConfig(
                  enabled = true,
                  automationEnabled = true,
                )
              )
            )
        }(config)
      )
      .withManualStart

  "report cometBft status" in { implicit env =>
    initSvc()
    forAll(env.svs.local) { sv =>
      eventually() {
        sv.cometBftNodeStatus().catchingUp shouldBe false
      }
    }
  }

  "get the debug dump for cometBft" in { implicit env =>
    initSvc()
    forAll(env.svs.local) { sv =>
      eventually() {
        sv.cometBftNodeDump().abciInfo.isObject shouldBe true
      }
    }
  }

  "sv1 starts as the genesis validator" in { implicit env =>
    initSvcWithSv1Only()
    withClue("Configured validator voting power is eventually reconciled") {
      eventually() {
        sv1Backend.cometBftNodeStatus().votingPower.doubleValue should be(1d)
      }
    }
  }

  "all nodes become validators" in { implicit env =>
    initSvc()
    forAll(env.svs.local) { sv =>
      eventually(timeUntilSuccess = 2.minute) {
        withClue(s"CometBFT node for ${sv.name} becomes a validator") {
          val dump = sv.cometBftNodeDump()
          logger.debug(s"Node dump for ${sv.name}: $dump")
          sv.cometBftNodeStatus().votingPower.doubleValue should be(1d)
        }
      }
    }
  }

}
