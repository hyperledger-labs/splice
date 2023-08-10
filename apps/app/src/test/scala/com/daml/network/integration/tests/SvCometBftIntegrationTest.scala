package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_RemoveMember
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  SvcRules_RemoveMember,
}
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.SvAppBackendReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.CometBftNetworkPlugin
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.cometbft.{CometBftConnectionConfig, CometBftHttpRpcClient}
import com.daml.network.sv.config.CometBftConfig
import com.daml.network.util.SvTestUtil
import com.digitalasset.canton.drivers.cometbft.data.{CometBftTx, SequencerTx}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.NamedLoggerFactory
import monocle.macros.syntax.lens.*

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Random

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

  "removed SV member has its node removed" in { implicit env =>
    eventually(timeUntilSuccess = 2.minute) {
      sv4Backend.cometBftNodeStatus().votingPower.doubleValue should be(1d)
    }
    val action: ActionRequiringConfirmation =
      new ARC_SvcRules(
        new SRARC_RemoveMember(
          new SvcRules_RemoveMember(sv4Backend.getSvcInfo().svParty.toProtoPrimitive)
        )
      )
    sv4Backend.stop()
    sv1Backend.createVoteRequest(
      sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
      action,
      "url",
      "description",
    )
    Seq(sv2Backend, sv3Backend).foreach { sv =>
      eventually() {
        sv.listVoteRequests() should not be empty
        val voteId = sv.listVoteRequests().head.contractId
        sv.castVote(voteId, isAccepted = true, "url", "description")
      }
    }
    eventually(timeUntilSuccess = 2.minute, maxPollInterval = 1.second) {
      sendEmptyTransactionToIncreaseBlockHeight(Random.shuffle(svs).head)
      cometbftClientForSvApp(sv4Backend)
        .nodeStatus()
        .valueOrFail("sv4 node status")
        .futureValue
        .validatorInfo
        .votingPower
        .toDouble should be(0d)
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
      .sendAndWaitForCommit(
        CometBftTx(
          CometBftTx.Message.SequencerTx(
            SequencerTx(
              // We send a sequencer transaction with a random UUID because sending the same transaction would be rejected as a duplicate
              // by the CometBFT memory pool
              uuid = UUID.randomUUID().toString
            )
          )
        ).toByteArray
      )
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
