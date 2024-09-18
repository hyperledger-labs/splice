// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.cometbft

import cats.data.EitherT
import cats.implicits.catsSyntaxTuple4Semigroupal
import com.daml.network.http.v0.definitions.CometBftJsonRpcRequestId
import com.daml.network.sv.cometbft.CometBftClient.{CometBftNodeDump, GovernanceAbciQueryParams}
import com.digitalasset.canton.drivers.cometbft.data.CometBftTx
import com.digitalasset.canton.drivers.cometbft.{
  GetNetworkConfigResponse,
  UpdateNetworkConfigRequest,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import io.circe.syntax.EncoderOps
import io.grpc.Status

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

class CometBftClient(client: CometBftHttpRpcClient, val loggerFactory: NamedLoggerFactory)
    extends NamedLogging {

  def readNetworkConfig()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[GetNetworkConfigResponse] = client
    .query(GovernanceAbciQueryParams)
    .map { response =>
      val byteValue = Base64.getDecoder.decode(response.value)
      GetNetworkConfigResponse.parseFrom(byteValue)
    }
    .rethrowAsGrpcException("read network config")

  def updateNetworkConfig(
      request: UpdateNetworkConfigRequest
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    val updateRequest = CometBftTx(CometBftTx.Message.UpdateNetworkConfigRequest(request))
    client
      .sendAndWaitForCommit(updateRequest.toByteArray)
      .rethrowAsGrpcException("update network config")
  }

  def nodeStatus()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[CometBftHttpRpcClient.NodeStatus] =
    client
      .nodeStatus()
      .rethrowAsGrpcException("node status")

  def jsonRpcCall(
      id: CometBftJsonRpcRequestId,
      method: String,
      params: Map[String, Json] = Map.empty,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[CometBftHttpRpcClient.CometBftCallResponse[Json]] = {
    client
      .rawCallFullResponse(method, params, Some(id))
      .rethrowAsGrpcException("json rpc api call")
  }

  def nodeDebugDump()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[CometBftNodeDump] = {
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
    }.rethrowAsGrpcException("node debug dump")
  }

  private def cometBftErrorToGrpcStatus(error: CometBftHttpRpcClient.CometBftError) = {
    error match {
      case CometBftHttpRpcClient.CometBftHttpError(_, error)
          if error.errMessage.contains("tx already exists in cache") =>
        Status.ABORTED.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(code, error) if code > 500 =>
        Status.UNAVAILABLE.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(code, error) if code == 500 =>
        Status.UNKNOWN.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(code, error) if code == 429 =>
        Status.ABORTED.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(code, error) if code == 404 =>
        Status.NOT_FOUND.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(code, error) if code == 403 =>
        Status.PERMISSION_DENIED.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(code, error) if code == 401 =>
        Status.UNAUTHENTICATED.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(code, error) if code >= 400 =>
        Status.FAILED_PRECONDITION.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(_, error) =>
        Status.INTERNAL.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftDecodeError(message, _, _) =>
        Status.INTERNAL.withDescription(message)
      case CometBftHttpRpcClient.CometBftAbciAppError(message) =>
        // We use FAILED_PRECONDITION, as most problems will eventually go away; and iterated retries are visible in our logs
        // TODO(#5897): raise different error codes once the ABCI app follows gRPC guidelines, currently it just always uses code 1
        Status.FAILED_PRECONDITION.withDescription(message)
    }
  }

  implicit class CometBftRethrowExtension[T](
      response: EitherT[Future, CometBftHttpRpcClient.CometBftError, T]
  ) {

    def rethrowAsGrpcException(
        cometBftCall: String
    )(implicit ec: ExecutionContext, tc: TraceContext): Future[T] = {
      response.leftMap { error =>
        logger.info(s"Failed to call CometBFT [$cometBftCall]: $error")
        cometBftErrorToGrpcStatus(error).asRuntimeException()
      }.rethrowT
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
