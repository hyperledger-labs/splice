package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.SvAppBackendReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.CometBftNetworkPlugin
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.cometbft.CometBftHttpRpcClient.CometBftHttpError
import com.daml.network.sv.cometbft.{CometBftConnectionConfig, CometBftHttpRpcClient}
import com.daml.network.sv.config.CometBftConfig
import com.daml.network.util.SvTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.NamedLoggerFactory
import monocle.macros.syntax.lens.*

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

class SvCometBftIntegrationTest extends CNNodeIntegrationTest with SvTestUtil {

  import ExecutionContext.Implicits.global
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
                  enabled = true
                )
              )
            )
            .focus(_.automation.enableCometbftReconciliation)
            .replace(true)
        }(config)
      )
      .withManualStart

  "report cometBft status and debug dump" in { implicit env =>
    initSvc()
    forAll(env.svs.local) { sv =>
      eventually() {
        sv.cometBftNodeStatus().catchingUp shouldBe false
        sv.cometBftNodeDump().abciInfo.isObject shouldBe true
      }
    }
  }

  "sv1 starts as the genesis validator" in { implicit env =>
    initSvcWithSv1Only()
    withClue("Configured validator voting power is eventually reconciled") {
      eventually() {
        sendEmptyTransactionToIncreaseBlockHeight(sv1Backend)
        sv1Backend.cometBftNodeStatus().votingPower.doubleValue should be(1d)
      }
    }
  }

  "all nodes become validators" in { implicit env =>
    initSvc()
    forAll(env.svs.local) { sv =>
      eventually(timeUntilSuccess = 2.minute) {
        withClue(s"CometBFT node for ${sv.name} becomes a validator") {
          sendEmptyTransactionToIncreaseBlockHeight(sv)
          val dump = sv.cometBftNodeDump()
          logger.debug(s"Node dump for ${sv.name}: $dump")
          sv.cometBftNodeStatus().votingPower.doubleValue should be(1d)
        }
      }
    }
  }

  // The changes to a validator is visible only in height H+1, and take effect in H+2,
  // therefore for us to see the changes we need to make sure the height increases
  private def sendEmptyTransactionToIncreaseBlockHeight(
      sv: SvAppBackendReference
  ): Unit = {
    new CometBftHttpRpcClient(
      CometBftConnectionConfig(sv.config.cometBftConfig.value.connectionUri),
      NamedLoggerFactory.root,
    ).sendAndWaitForCommit(Array.emptyByteArray)
      .recover {
        case CometBftHttpError(_, error)
            if error.error.noSpaces.contains("tx already exists in cache") =>
          ()
      }
      .valueOrFail("empty transaction")
      .futureValue
  }

}
