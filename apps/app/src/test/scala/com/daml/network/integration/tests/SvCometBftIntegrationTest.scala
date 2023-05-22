package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.cometbft.SvCometBftNetwork
import com.daml.network.sv.config.CometBftConfig
import com.daml.network.util.SvTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

class SvCometBftIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with SvTestUtil
    with SvCometBftNetwork {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs { (name, config) =>
          config
            .focus(_.cometBftConfig)
            .replace(
              Some(
                CometBftConfig(
                  enabled = true,
                  connectionUri = startNewCometBftContainer(name).uri,
                )
              )
            )
        }(config)
      )

  "report cometBft status" in { implicit env =>
    forAll(env.svs.local) { sv =>
      eventually() {
        sv.cometBftNodeStatus().catchingUp shouldBe false
      }
    }
  }

  "get the debug dump for cometBft" in { implicit env =>
    forAll(env.svs.local) { sv =>
      eventually() {
        sv.cometBftNodeDump().abciInfo.isObject shouldBe true
      }
    }
  }

  "only the SV1 CometBFT node is a validator" in { implicit env =>
    eventually() {
      env.svs.local.foreach { sv =>
        withClue(s"sv ${sv.name} must know all the other BFT nodes as peers") {
          sv.cometBftNodeDump()
            .networkInfo
            .findAllByKey("n_peers")
            .loneElement
            .as[Integer]
            .value shouldBe 3
        }
      }
      // SV1 always starts first, and it's set as a validator in the genesis
      sv1.cometBftNodeStatus().votingPower.doubleValue shouldBe 10
      // Validate all other nodes are not validators
      forAll(env.svs.local.filterNot(_.name == sv1.name)) { sv =>
        sv.cometBftNodeStatus().votingPower.doubleValue shouldBe 0
      }
    }
  }

}
