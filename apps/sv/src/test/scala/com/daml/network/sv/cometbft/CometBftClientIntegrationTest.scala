// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.

package com.daml.network.sv.cometbft

import com.daml.network.sv.cometbft.CometBftClientIntegrationTest.{
  InitialVotingPower,
  PubKey2,
  SvNode2,
  createUpdateNetworkConfigRequest,
}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.drivers.cometbft.NetworkConfigChangeRequest.Kind.NodeConfigChangeRequest
import com.digitalasset.canton.drivers.cometbft.SvNodeConfigChange.Kind.SetConfig
import com.digitalasset.canton.drivers.cometbft.{
  CometBftNodeConfig,
  NetworkConfigChangeRequest,
  SvNodeConfig,
  SvNodeConfigChange,
  SvNodeConfigChangeRequest,
  UpdateNetworkConfigRequest,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.google.protobuf.ByteString
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.ExecutionContext

class CometBftClientIntegrationTest
    extends AsyncWordSpec
    with BaseTest
    with CometBftContainerAround {

  override implicit def executionContext: ExecutionContext = ExecutionContext.global
  lazy val client = new CometBftHttpRpcClient(connectionConfig, NamedLoggerFactory.root)
  private lazy val cometBftClient = new CometBftClient(client, loggerFactory)

  "CometBFT Canton Network ABCI app" should {
    "get the initial network config state" in {
      cometBftClient
        .readNetworkConfig()
        .map { networkConfig =>
          networkConfig.chainId should startWith("test-chain-")
          val initialState = networkConfig.svNodeConfigStates.get("initial").value
          initialState.currentConfigRevision shouldBe 1
          initialState.pendingChanges shouldBe empty
          val currentConfig = initialState.currentConfig.value
          val (svNodeId, cometBftNodeConfig) = currentConfig.cometbftNodes.head
          svNodeId shouldNot be(empty)
          cometBftNodeConfig.validatorPubKey shouldNot be(empty)
          cometBftNodeConfig.votingPower should be(InitialVotingPower)
        }
    }

    "apply a network config change" in {
      for {
        networkConfig <- cometBftClient
          .readNetworkConfig()
          .valueOrFail("Failed to read network config")
        chainId = networkConfig.chainId
        _ <- cometBftClient
          .updateNetworkConfig(
            createUpdateNetworkConfigRequest(
              chainId = chainId,
              submitterSvNodeId = SvNode2,
              changedSvNodeId = SvNode2,
              pubKey = PubKey2,
            )
          )
          .valueOrFail("Failed to update network config")
      } yield {
        eventually() {
          cometBftClient
            .readNetworkConfig()
            .map { networkConfig =>
              val newState = networkConfig.svNodeConfigStates.get(SvNode2).value
              newState.currentConfigRevision shouldBe 1
              newState.pendingChanges should be(empty)
              val currentConfig = newState.currentConfig.value
              val nodes = currentConfig.cometbftNodes
              nodes shouldNot be(empty)
              val node = nodes.get("nodeId").value
              node.validatorPubKey shouldBe PubKey2
              node.votingPower shouldBe 1
            }
            .futureValue
        }
      }
    }
  }

  "CometBFT status call" should {

    "read status" in {
      cometBftClient
        .nodeStatus()
        .map { status =>
          status.validatorInfo.votingPower.toDouble should be > 0d
          status.syncInfo.catchingUp shouldBe false
        }
        .valueOrFail("Reading status")
    }

  }

}

object CometBftClientIntegrationTest {
  private val InitialVotingPower = 10L

  private val PubKey2 = "pubKey2"
  private val SvNode2 = "svNode2"

  private def createUpdateNetworkConfigRequest(
      chainId: String,
      submitterSvNodeId: String,
      changedSvNodeId: String,
      pubKey: String,
  ) =
    UpdateNetworkConfigRequest.of(
      changeRequestBytes = NetworkConfigChangeRequest(
        chainId,
        submitterSvNodeId,
        NodeConfigChangeRequest(
          SvNodeConfigChangeRequest.of(
            changedSvNodeId,
            currentConfigRevision = 0L,
            change = Some(
              SvNodeConfigChange.of(
                SetConfig(
                  SvNodeConfig.of(
                    Map("nodeId" -> CometBftNodeConfig.of(pubKey, votingPower = 1))
                  )
                )
              )
            ),
          )
        ),
      ).toByteString,
      signature = ByteString.copyFromUtf8("signature"),
    )

}
