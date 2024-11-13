// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.cometbft

import cats.data.EitherT
import cats.syntax.either.*
import org.lfdecentralizedtrust.splice.http.v0.definitions.CometBftJsonRpcRequestId
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftHttpRpcClient.CometBftCallResponse.{
  cometBftBroadcastResultDecoder,
  queryDecoder,
}
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftHttpRpcClient.NodeStatus.{
  NodeInfo,
  SyncInfo,
  ValidatorInfo,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.*
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}
import io.grpc.Status

import java.net.http.HttpClient
import java.util.concurrent.Executor
import java.util.{Base64, UUID}
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.{ExecutionContext, Future}

class CometBftHttpRpcClient(
    conf: CometBftConnectionConfig,
    override val loggerFactory: NamedLoggerFactory,
)(implicit executionContext: ExecutionContext)
    extends NamedLogging {
  import CometBftHttpRpcClient.*

  private val executor = new JavaExecutor(executionContext)

  private val httpClientBuilder = HttpClient
    .newBuilder()
    .executor(executor)
    .build()

  def health(): Future[CometBftHealth] =
    callCometBftHttp("health").toScala.map { resp =>
      resp.statusCode() match {
        case 200 => HealthyCometBftNode
        case status => UnhealthyCometBftNode(status)
      }
    }

  def rawCall(
      path: String,
      params: Map[String, Json] = Map.empty,
      id: Option[CometBftJsonRpcRequestId] = None,
  ): EitherT[Future, CometBftError, Json] = {
    callCometBftJsonHttp[Json](path, params, id).map(_.result)
  }

  def rawCallFullResponse(
      path: String,
      params: Map[String, Json] = Map.empty,
      id: Option[CometBftJsonRpcRequestId] = None,
  ): EitherT[Future, CometBftError, CometBftCallResponse[Json]] = {
    callCometBftJsonHttp[Json](path, params, id)
  }

  def nodeStatus(): EitherT[Future, CometBftError, NodeStatus] = {
    callCometBftJsonHttp[NodeStatus]("status", Map.empty[String, Json]).map(_.result)
  }

  def query(queryParams: Map[String, Json]): EitherT[Future, CometBftError, QueryResponse] = {
    callCometBftJsonHttp[CometBftQueryResult](AbciQueryMethod, queryParams).map(response =>
      QueryResponse(response.result.response.value)
    )
  }

  def sendAndWaitForCommit(
      message: Array[Byte]
  ): EitherT[Future, CometBftError, Unit] = {
    val encodedRequest =
      Base64.getEncoder.encodeToString(message)

    def extractAppError(execResult: CometBftTxExecutionResult): Option[CometBftAbciAppError] =
      Option.when(execResult.code != Status.Code.OK.value())(CometBftAbciAppError(execResult.log))

    // We are using `broadcast_tx_commit` here despite the docs not recommending it, as we believe that the main problem with
    // `broadcast_tx_commit` is performance: the implementation keeps a subscription open for each open request and
    // there's a configured limit (default: max_subscription_clients = 100) for how many such subscriptions can be open
    // at the same time. We believe this is OK for governance txs, as they are submitted at a low enough rate.
    // The main benefit of using `broadcast_tx_commit` is that the reconciliation trigger waits for the complete
    // submission before looping and errors are propagated properly.
    callCometBftJsonHttp[CometBftBroadcastResult](
      "broadcast_tx_commit",
      Map("tx" -> encodedRequest.asJson),
    ).flatMap(response => {
      // DeliverTx refers to the sequential tx processing within block commit. Errors there are more precise, than
      // errors in CheckTx, which guards the mempool. See https://github.com/tendermint/tendermint/issues/2249
      val optAppError = response.result.deliverTx
        .flatMap(extractAppError)
        .orElse(
          extractAppError(response.result.checkTx)
        )
      EitherT.fromEither(optAppError.toLeft(()))
    })
  }

  // TODO(#5428) add retries for failures
  private def callCometBftJsonHttp[T: Decoder](
      method: String,
      params: Map[String, Json],
      id: Option[CometBftJsonRpcRequestId] = None,
  ): EitherT[Future, CometBftError, CometBftCallResponse[T]] = EitherT {
    // if not provided, id is set to a unique id for correlating requests with responses
    // without an id the request is treated fully async
    val toSend =
      s"""{
         |  "jsonrpc": "2.0",
         |  "method": "$method",
         |  "params": ${params.asJson.noSpaces},
         |  "id": ${id.fold(UUID.randomUUID().toString.asJson)(_.asJson)}
         |}""".stripMargin
    httpClientBuilder
      .sendAsync(
        java.net.http.HttpRequest
          .newBuilder()
          .uri(
            encodeURI()
          )
          .header("Content-type", "application/json")
          .method(
            "POST",
            java.net.http.HttpRequest.BodyPublishers.ofString(toSend),
          )
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString(),
      )
      .toScala
      .map { response =>
        // Handle response and parse body as per doc: https://docs.tendermint.com/v0.34/rpc/#/ABCI/abci_query
        val responseBody = response.body()
        if (response.statusCode() == 200) {
          val response = decode[CometBftCallResponse[T]](responseBody)
          response.leftMap(decodeError =>
            // Errors are sometimes propagated with status 200, so we try to decode the error as well
            decode[CometBftJsonErrorResponse](responseBody)
              .fold[CometBftError](
                errDecodingFailure =>
                  CometBftDecodeError(
                    s"Failed to decode response as success (${decodeError.toString}) or error (${errDecodingFailure.toString}).",
                    responseBody,
                    200,
                  ),
                CometBftHttpError(
                  200,
                  _,
                ),
              )
          )
        } else {
          decode[CometBftJsonErrorResponse](responseBody)
            .fold[CometBftError](
              err =>
                // in some cases (eg. if the cometbft api server is down), we can receive a 5xx response from the proxy/load balancer
                if (response.statusCode() >= 500) {
                  CometBftHttpError(
                    response.statusCode(),
                    CometBftHttpErrorResponse(responseBody),
                  )
                } else {
                  // raise a decode error if we could neither decode a json error response nor is it a 5xx status code
                  CometBftDecodeError(
                    s"Failed to parse json response: $err.",
                    responseBody,
                    response.statusCode(),
                  )
                },
              // the response body could be successfully decoded as a JSON error response
              CometBftHttpError(
                response.statusCode(),
                _,
              ),
            )
            .asLeft[CometBftCallResponse[T]]
        }
      }
  }

  private def callCometBftHttp(action: String) =
    httpClientBuilder
      .sendAsync(
        java.net.http.HttpRequest
          .newBuilder()
          .uri(
            encodeURI(endpoint = s"/$action")
          )
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString(),
      )

  private def encodeURI(endpoint: String = "") =
    java.net.URI.create(
      s"${conf.uri}$endpoint"
    )

}

object CometBftHttpRpcClient {

  private val AbciQueryMethod = "abci_query"

  private[cometbft] sealed trait CometBftJsonHttpResponse {
    def jsonrpc: String
    def id: CometBftJsonRpcRequestId // id could be a string (auto-generated UUID in our code) or a number (used by the CometBft Light client during state sync)
  }

  private[cometbft] sealed trait CometBftErrorResponse {
    def errMessage: String
  }

  private[cometbft] case class CometBftHttpErrorResponse(
      error: String
  ) extends CometBftErrorResponse {
    def errMessage: String = error
  }

  // Ideally error should be a `String`, as defined by the API spec, but it seems that it's not really enforced and sometimes it's an object
  private[cometbft] case class CometBftJsonErrorResponse(
      jsonrpc: String,
      id: CometBftJsonRpcRequestId,
      error: Json,
  ) extends CometBftJsonHttpResponse
      with CometBftErrorResponse {
    def errMessage: String = error.noSpaces
  }
  private[cometbft] object CometBftJsonErrorResponse {
    implicit val jsonErrorDecoder: Decoder[CometBftJsonErrorResponse] =
      deriveDecoder[CometBftJsonErrorResponse]
  }

  private[cometbft] case class CometBftCallResponse[T](
      jsonrpc: String,
      id: CometBftJsonRpcRequestId,
      result: T,
  ) extends CometBftJsonHttpResponse

  private[cometbft] object CometBftCallResponse {
    implicit val responseDecoder: Decoder[CometBftQueryResponse] =
      deriveDecoder[CometBftQueryResponse]
    implicit val queryDecoder: Decoder[CometBftQueryResult] = deriveDecoder
    implicit val txExecutionResultDecoder: Decoder[CometBftTxExecutionResult] = deriveDecoder

    implicit val cometBftBroadcastResultDecoder: Decoder[CometBftBroadcastResult] =
      Decoder.forProduct2("deliver_tx", "check_tx")(CometBftBroadcastResult.apply)

    implicit def successDecoder[T: Decoder]: Decoder[CometBftCallResponse[T]] = deriveDecoder
    implicit def responseEncoder[T: Encoder]: Encoder[CometBftCallResponse[T]] = deriveEncoder
  }

  case class NodeStatus(nodeInfo: NodeInfo, syncInfo: SyncInfo, validatorInfo: ValidatorInfo)
  private[cometbft] object NodeStatus {

    case class NodeInfo(id: String)

    implicit val nodeInfoDecoder: Decoder[NodeInfo] = deriveDecoder

    case class CantonBftNodePublicKey(value: String)
    implicit val cantonBftNodePublicKeyDecoder: Decoder[CantonBftNodePublicKey] = deriveDecoder

    case class ValidatorInfo(votingPower: String, publicKey: CantonBftNodePublicKey)

    implicit val validatorInfoDecoder: Decoder[ValidatorInfo] =
      Decoder.forProduct2("voting_power", "pub_key")(ValidatorInfo.apply)

    case class SyncInfo(latestBlockHeight: Long, earliestBlockHeight: Long, catchingUp: Boolean)

    implicit val syncInfoDecoder: Decoder[SyncInfo] =
      Decoder.forProduct3("latest_block_height", "earliest_block_height", "catching_up")(
        SyncInfo.apply
      )

    implicit val nodeStatusDecoder: Decoder[NodeStatus] =
      Decoder.forProduct3("node_info", "sync_info", "validator_info")(NodeStatus.apply)

  }

  private[cometbft] case class CometBftTxExecutionResult(code: Int, log: String)

  private[cometbft] case class CometBftBroadcastResult(
      checkTx: CometBftTxExecutionResult,
      deliverTx: Option[CometBftTxExecutionResult],
  )
  private[cometbft] case class CometBftQueryResult(response: CometBftQueryResponse)
  private[cometbft] case class CometBftQueryResponse(value: String)

  sealed trait CometBftError

  case class CometBftHttpError(code: Int, response: CometBftErrorResponse) extends CometBftError
  case class CometBftDecodeError(message: String, body: String, status: Int) extends CometBftError

  case class CometBftAbciAppError(message: String) extends CometBftError

  private[cometbft] case class QueryResponse(value: String)
}

class JavaExecutor(val ec: ExecutionContext) extends Executor {
  override def execute(command: Runnable): Unit = ec.execute(command)
}
