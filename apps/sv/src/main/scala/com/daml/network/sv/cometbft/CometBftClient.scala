package com.daml.network.sv.cometbft

import java.util.Base64

import cats.data.EitherT
import cats.implicits.catsSyntaxTuple4Semigroupal
import com.daml.network.sv.cometbft.CometBftClient.{CometBftNodeDump, GovernanceAbciQueryParams}
import com.daml.network.sv.cometbft.CometBftHttpRpcClient.NodeStatus
import com.digitalasset.canton.drivers.cometbft.{
  CometBftTx,
  GetNetworkConfigResponse,
  UpdateNetworkConfigRequest,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import io.circe.syntax.EncoderOps

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

  def nodeDebugDump()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): EitherT[Future, CometBftHttpRpcClient.QueryError, CometBftNodeDump] = {
    val FirstPageNumber = "1"
    val MaxAcceptedValidatorsPerPage = "100"
    (
      client.rawCall("status"),
      client.rawCall("net_info"),
      // Get the first 100 validators, no need to paginate as we are safely under the limit of 100 validators returned per page
      // Height is not provided in the request as we want to fetch the validator set for the latest block
      //  if height is provided it must be a valid block height as the API does not accept the default value (0) as input
      client.rawCall(
        "validators",
        Map(
          "page" -> FirstPageNumber.asJson,
          "per_page" -> MaxAcceptedValidatorsPerPage.asJson,
        ),
      ),
      client.rawCall("abci_info"),
    ).mapN { case (status, netInfo, validators, abciInfo) =>
      CometBftNodeDump(
        status,
        netInfo,
        validators,
        abciInfo,
      )
    }.leftMap { error =>
      logger.warn(s"Failed to generate CometBFT debug dump: $error")
      error
    }
  }
}

object CometBftClient {

  private val EmptyQueryData = ""
  private val LatestHeight = "0"
  private val ProofOfTransactionsInclusionInBlock = false

  private val GovernanceAbciQueryParams = Map(
    "path" -> "/governance".asJson,
    "data" -> EmptyQueryData.asJson,
    "height" -> LatestHeight.asJson,
    "prove" -> ProofOfTransactionsInclusionInBlock.asJson,
  )

  case class CometBftNodeDump(
      status: Json,
      networkInfo: Json,
      validators: Json,
      abciInfo: Json,
  )
}
