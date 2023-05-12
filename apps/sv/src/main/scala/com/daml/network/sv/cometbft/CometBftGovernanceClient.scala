package com.daml.network.sv.cometbft

import java.util.Base64

import cats.data.EitherT
import com.daml.network.sv.cometbft.CometBftGovernanceClient.GovernanceAbciQueryParams
import com.digitalasset.canton.drivers.cometbft.{
  CometBftTx,
  GetNetworkConfigResponse,
  UpdateNetworkConfigRequest,
}

import scala.concurrent.{ExecutionContext, Future}

class CometBftGovernanceClient(client: CometBftClient) {

  def readNetworkConfig()(implicit
      ec: ExecutionContext
  ): EitherT[Future, CometBftClient.QueryError, GetNetworkConfigResponse] = {
    client
      .query(GovernanceAbciQueryParams)
      .map { response =>
        val byteValue = Base64.getDecoder.decode(response.value)
        GetNetworkConfigResponse.parseFrom(byteValue)
      }
  }

  def updateNetworkConfig(
      request: UpdateNetworkConfigRequest
  ): EitherT[Future, CometBftClient.QueryError, Unit] = {
    val updateRequest = CometBftTx(CometBftTx.Message.UpdateNetworkConfigRequest(request))
    client.send(updateRequest.toByteArray)
  }

}

object CometBftGovernanceClient {

  private val EmptyQueryData = ""
  private val LatestHeight = 0
  private val ProofOfTransactionsInclusionInBlock = false

  private val GovernanceAbciQueryParams =
    s"""["/governance","$EmptyQueryData","$LatestHeight",$ProofOfTransactionsInclusionInBlock]"""
}
