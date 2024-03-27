package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import com.daml.network.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_OffboardSv
import com.daml.network.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_OffboardSv,
}
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.SvAppBackendReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions.{CometBftJsonRpcRequest, CometBftJsonRpcRequestId}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.CometBftNetworkPlugin
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.cometbft.{CometBftConnectionConfig, CometBftHttpRpcClient}
import com.daml.network.sv.config.CometBftConfig
import com.daml.network.util.SvTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.NamedLoggerFactory
import io.circe.Json
import monocle.macros.syntax.lens.*

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

class SvCometBftIntegrationTest extends CNNodeIntegrationTestWithSharedEnvironment with SvTestUtil {

  import ExecutionContext.Implicits.global
  registerPlugin(new CometBftNetworkPlugin("sv_cometbft_integration_test", NamedLoggerFactory.root))

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
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
      // TODO(#8300) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "all nodes become validators" in { implicit env =>
    forAll(env.svs.local) { sv =>
      eventually(timeUntilSuccess = 2.minute) {
        withClue(s"CometBFT node for ${sv.name} becomes a validator") {
          cometBFTnodeIsUpToDateValidator(sv)
        }
      }
    }
  }

  "removed SV has its node removed" in { implicit env =>
    eventually(timeUntilSuccess = 2.minute) {
      sv4Backend.cometBftNodeStatus().votingPower.doubleValue should be(1d)
    }
    val action: ActionRequiringConfirmation =
      new ARC_DsoRules(
        new SRARC_OffboardSv(
          new DsoRules_OffboardSv(sv4Backend.getDsoInfo().svParty.toProtoPrimitive)
        )
      )
    sv4Backend.stop()
    sv1Backend.createVoteRequest(
      sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
      action,
      "url",
      "description",
      sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
    )
    val trackingCid = sv1Backend.getLatestVoteRequestTrackingCid()
    Seq(sv2Backend, sv3Backend).foreach { sv =>
      eventually() {
        sv.listVoteRequests() should not be empty
        sv.castVote(trackingCid, isAccepted = true, "url", "description")
      }
    }
    eventually(timeUntilSuccess = 2.minute, maxPollInterval = 1.second) {
      cometbftClientForSvApp(sv4Backend)
        .nodeStatus()
        .valueOrFail("sv4 node status")
        .futureValue
        .validatorInfo
        .votingPower
        .toDouble should be(0d)
    }
    // Wait until the namespace change goes through, otherwise the namespace reset trigger might break.
    eventually() {
      Seq(sv1Backend, sv2Backend, sv3Backend).foreach { sv =>
        sv.participantClient.topology.decentralized_namespaces
          .list(
            filterStore = globalDomainId.filterString,
            filterNamespace = dsoParty.uid.namespace.toProtoPrimitive,
          )
          .loneElement
          .item
          .owners
          .forgetNE should have size 3
      }
    }
  }

  // In case this test fails, please also test CometBFT state sync is working by:
  // - deploying a scratchnet cluster
  // - waiting a while for the block height to grow beyond the minTrustHeightAge configured in the cometbft helm chart
  // - deploying the SV runbook
  "Sv app" should {
    "expose CometBFT RPC methods required for state sync" in { implicit env =>
      testJsonRpcCall(1, "status", Map.empty, Seq("node_info", "sync_info", "validator_info"))
      testJsonRpcCall(2, "block", Map("height" -> Json.fromString("1")), Seq("block_id", "block"))
      testJsonRpcCall(3, "commit", Map("height" -> Json.fromString("1")), Seq("signed_header"))
      testJsonRpcCall(
        4,
        "validators",
        Map("height" -> Json.fromString("1")),
        Seq("block_height", "validators"),
      )
      testJsonRpcCall(
        5,
        "consensus_params",
        Map("height" -> Json.fromString("1")),
        Seq("block_height", "consensus_params"),
      )
    }
  }

  private def cometBFTnodeIsUpToDateValidator(sv: SvAppBackendReference) = {
    // node is up to date
    sv.cometBftNodeStatus().catchingUp shouldBe false
    // validate dump
    sv.cometBftNodeDump().abciInfo.isObject shouldBe true
    sv.cometBftNodeStatus().votingPower.doubleValue should be(1d)
  }

  private def cometbftClientForSvApp(sv: SvAppBackendReference) = {
    new CometBftHttpRpcClient(
      CometBftConnectionConfig(sv.config.cometBftConfig.value.connectionUri),
      NamedLoggerFactory.root,
    )
  }

  private def testJsonRpcCall(
      id: Long,
      method: String,
      params: Map[String, Json],
      responseKeys: Seq[String],
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = {
    val id_ = CometBftJsonRpcRequestId.fromNested2(id)
    val method_ = CometBftJsonRpcRequest.Method.from(method).value
    val response = sv1Backend.cometBftJsonRpcRequest(id_, method_, params)
    response.id shouldBe id_
    response.jsonrpc shouldBe "2.0"
    responseKeys.foreach(key => response.result.findAllByKey(key) should not be empty)
  }

}
