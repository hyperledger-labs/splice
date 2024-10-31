// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.

package org.lfdecentralizedtrust.splice.sv.cometbft

import org.lfdecentralizedtrust.splice.http.v0.definitions.CometBftJsonRpcRequestId
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftClientIntegrationTest.{
  InitialVotingPower,
  PubKey2,
  SvNode1,
  SvNode2,
  SvNode2CometId,
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
import org.scalatest.wordspec.AsyncWordSpec
import scalapb.TimestampConverters

import java.time.Instant
import scala.concurrent.ExecutionContext

class CometBftClientIntegrationTest
    extends AsyncWordSpec
    with BaseTest
    with CometBftContainerAround {

  override implicit def executionContext: ExecutionContext = ExecutionContext.global
  lazy val client = new CometBftHttpRpcClient(connectionConfig, NamedLoggerFactory.root)
  private lazy val cometBftClient = new CometBftClient(client, loggerFactory)

  "CometBFT Splice ABCI app" should {
    "get the initial network config state" in {
      cometBftClient
        .readNetworkConfig()
        .map { networkConfig =>
          networkConfig.chainId should startWith("test-chain-")
          val initialState = networkConfig.svNodeConfigStates.get(SvNode1).value
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
        chainId = networkConfig.chainId
        _ <- cometBftClient
          .updateNetworkConfig(
            createUpdateNetworkConfigRequest(
              chainId = chainId,
              submitterSvNodeId = SvNode1,
              changedSvNodeId = SvNode2,
              pubKey = PubKey2,
            )
          )
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
              val node = nodes.get(SvNode2CometId).value
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
    }

    "create dump" in {
      cometBftClient
        .nodeDebugDump()
        .map { dump =>
          // Validate some basic data from each result as per RPC doc: https://docs.cometbft.com/v0.37/rpc/#/Info/status
          dump.abciInfo.findAllByKey("app_version") should not be empty
          dump.validators.findAllByKey("address") should not be empty
          dump.networkInfo.findAllByKey("n_peers") should not be empty
          dump.status.findAllByKey("latest_block_height") should not be empty
        }
    }

    "json rpc call" in {
      val id = CometBftJsonRpcRequestId.fromNested2(0)
      cometBftClient
        .jsonRpcCall(id, "status")
        .map { response =>
          response.id shouldBe id
          response.result.findAllByKey("node_info") should not be empty
        }
    }

  }

}

object CometBftClientIntegrationTest {
  private val InitialVotingPower = 10L

  private val SvNode1 = "Digital-Asset-2"
  private val PubKey2 = "gpkwc1WCttL8ZATBIPWIBRCrb0eV4JwMCnjRa56REPw="
  private val SvNode2 = "svNode2"
  private val SvNode2CometId = "8A931AB5F957B8331BDEF3A0A081BD9F017A777F"

  private def createUpdateNetworkConfigRequest(
      chainId: String,
      submitterSvNodeId: String,
      changedSvNodeId: String,
      pubKey: String,
  ) = {
    val cometBftRequestSigner = CometBftRequestSigner.getGenesisSigner
    val request = NetworkConfigChangeRequest(
      chainId = chainId,
      submitterSvNodeId = submitterSvNodeId,
      submitterKeyId = cometBftRequestSigner.Fingerprint,
      submittedAt = Some(TimestampConverters.fromJavaInstant(Instant.now())),
      kind = NodeConfigChangeRequest(
        SvNodeConfigChangeRequest.of(
          changedSvNodeId,
          currentConfigRevision = 0L,
          change = Some(
            SvNodeConfigChange.of(
              SetConfig(
                SvNodeConfig(
                  Map(SvNode2CometId -> CometBftNodeConfig.of(pubKey, votingPower = 1))
                )
              )
            )
          ),
        )
      ),
    )

    UpdateNetworkConfigRequest.of(
      changeRequestBytes = request.toByteString,
      signature =
        com.google.protobuf.ByteString.copyFrom(cometBftRequestSigner.signRequest(request)),
    )
  }

}
