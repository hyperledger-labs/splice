package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.SvAppBackendReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.CometBftNetworkPlugin
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
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

class SvCometBftIntegrationTest extends CNNodeIntegrationTestWithSharedEnvironment with SvTestUtil {

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

  "all nodes become validators" in { implicit env =>
    forAll(env.svs.local) { sv =>
      eventually(timeUntilSuccess = 2.minute) {
        withClue(s"CometBFT node for ${sv.name} becomes a validator") {
          cometBFTnodeIsUpToDateValidator(sv)
        }
      }
    }
  }

  private def cometBFTnodeIsUpToDateValidator(sv: SvAppBackendReference) = {
    // node is up to date
    sv.cometBftNodeStatus().catchingUp shouldBe false
    // validate dump
    sv.cometBftNodeDump().abciInfo.isObject shouldBe true
    sendEmptyTransactionToIncreaseBlockHeight(sv)
    sv.cometBftNodeStatus().votingPower.doubleValue should be(1d)
  }

  // The changes to a validator is visible only in height H+1, and take effect in H+2,
  // therefore for us to see the changes we need to make sure the height increases
  private def sendEmptyTransactionToIncreaseBlockHeight(
      sv: SvAppBackendReference
  ): Unit = {
    cometbftClientForSvApp(sv)
      .sendAndWaitForCommit(Array.emptyByteArray)
      .recover {
        case CometBftHttpError(_, error)
            if error.error.noSpaces.contains("tx already exists in cache") =>
          ()
      }
      .valueOrFail("empty transaction")
      .futureValue
  }

  private def cometbftClientForSvApp(sv: SvAppBackendReference) = {
    new CometBftHttpRpcClient(
      CometBftConnectionConfig(sv.config.cometBftConfig.value.connectionUri),
      NamedLoggerFactory.root,
    )
  }
}
