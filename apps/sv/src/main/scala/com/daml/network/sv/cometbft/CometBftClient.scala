package com.daml.network.sv.cometbft

import java.util.Base64

import cats.data.EitherT
import com.daml.network.sv.cometbft.CometBftClient.GovernanceAbciQueryParams
import com.daml.network.sv.cometbft.CometBftHttpRpcClient.NodeStatus
import com.digitalasset.canton.drivers.cometbft.{
  CometBftTx,
  GetNetworkConfigResponse,
  UpdateNetworkConfigRequest,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

class CometBftClient(client: CometBftHttpRpcClient, val loggerFactory: NamedLoggerFactory)
    extends NamedLogging {

  def readNetworkConfig()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): EitherT[Future, CometBftHttpRpcClient.QueryError, GetNetworkConfigResponse] = {
    client
      .query(GovernanceAbciQueryParams)
      .map { response =>
        val byteValue = Base64.getDecoder.decode(response.value)
        GetNetworkConfigResponse.parseFrom(byteValue)
      }
      .leftMap { error =>
        logger.warn(s"Failed to read CometBFT network config: $error")
        error
      }
  }

  def updateNetworkConfig(
      request: UpdateNetworkConfigRequest
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): EitherT[Future, CometBftHttpRpcClient.QueryError, Unit] = {
    val updateRequest = CometBftTx(CometBftTx.Message.UpdateNetworkConfigRequest(request))
    client
      .send(updateRequest.toByteArray)
      .leftMap { error =>
        logger.warn(s"Failed to update CometBFT network config: $error")
        error
      }
  }

  def nodeStatus()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): EitherT[Future, CometBftHttpRpcClient.QueryError, NodeStatus] =
    client
      .nodeStatus()
      .leftMap { error =>
        logger.warn(s"Failed to query CometBFT node status: $error")
        error
      }

}

object CometBftClient {

  private val EmptyQueryData = ""
  private val LatestHeight = 0
  private val ProofOfTransactionsInclusionInBlock = false

  private val GovernanceAbciQueryParams =
    s"""["/governance","$EmptyQueryData","$LatestHeight",$ProofOfTransactionsInclusionInBlock]"""
}
