// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.

package com.daml.network.sv.cometbft

import cats.data.EitherT
import cats.syntax.either.*
import com.daml.network.sv.cometbft.CometBftHttpRpcClient.CometBftCallResponse.{
  broadcastDecoder,
  queryDecoder,
}
import com.daml.network.sv.cometbft.CometBftHttpRpcClient.NodeStatus.{
  NodeInfo,
  SyncInfo,
  ValidatorInfo,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.*
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Json}

import java.net.http.HttpClient
import java.util.concurrent.Executor
import java.util.{Base64, UUID}
import scala.annotation.nowarn
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
  ): EitherT[Future, CometBftError, Json] = {
    callCometBftJsonHttp[Json](path, params).map(_.result)
  }

  def nodeStatus(): EitherT[Future, CometBftError, NodeStatus] = {
    callCometBftJsonHttp[NodeStatus]("status", Map.empty).map(_.result)
  }

  def query(queryParams: Map[String, Json]): EitherT[Future, CometBftError, QueryResponse] = {
    callCometBftJsonHttp[CometBftQueryResult](AbciQueryMethod, queryParams).map(response =>
      QueryResponse(response.result.response.value)
    )
  }

  def send(
      message: Array[Byte]
  ): EitherT[Future, CometBftError, Unit] = {
    val encodedRequest =
      Base64.getEncoder.encodeToString(message)
    callCometBftJsonHttp[CometBftBroadcastResult](
      "broadcast_tx_async",
      Map("tx" -> encodedRequest.asJson),
    ).map(_ => {})
  }

  // TODO(#5428) add retries for failures
  private def callCometBftJsonHttp[T: Decoder](
      method: String,
      param: Map[String, Json],
  ): EitherT[Future, CometBftError, CometBftCallResponse[T]] = EitherT {
    // id is set to a unique id for correlating requests with responses
    // without an id the requests is treated fully async
    val toSend =
      s"""{
         |  "jsonrpc": "2.0",
         |  "method": "$method",
         |  "params": ${param.asJson.noSpaces},
         |  "id": "${UUID.randomUUID()}"
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
            decode[CometBftErrorResponse](responseBody)
              .fold(
                errDecodingFailure =>
                  CometBftError(
                    s"Failed to decode response as success (${decodeError.toString}) or error (${errDecodingFailure.toString}). Full response: $responseBody",
                    200,
                  ),
                errorResponse =>
                  CometBftError(
                    s"CometBFT call failed with error: $errorResponse",
                    200,
                  ),
              )
          )
        } else {
          decode[CometBftErrorResponse](responseBody)
            .fold(
              err =>
                CometBftError(
                  s"Failed to parse json response: $err. Original body response: $responseBody",
                  response.statusCode(),
                ),
              res => CometBftError(res.error.noSpaces, response.statusCode()),
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

@nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
object CometBftHttpRpcClient {

  private val AbciQueryMethod = "abci_query"

  // Ideally error should be a `String`, as defined by the API spec, but it seems that it's not really enforced and sometimes it's an object
  private[cometbft] case class CometBftErrorResponse(error: Json, id: Int)

  private[cometbft] object CometBftErrorResponse {
    implicit val errorDecoder: Decoder[CometBftErrorResponse] =
      deriveDecoder[CometBftErrorResponse]
  }
  private[cometbft] case class CometBftCallResponse[T](result: T)

  private[cometbft] object CometBftCallResponse {
    implicit val responseDecoder: Decoder[CometBftQueryResponse] =
      deriveDecoder[CometBftQueryResponse]
    implicit val queryDecoder: Decoder[CometBftQueryResult] = deriveDecoder
    implicit val broadcastDecoder: Decoder[CometBftBroadcastResult] = deriveDecoder

    @nowarn("cat=unused")
    implicit def successDecoder[T: Decoder]: Decoder[CometBftCallResponse[T]] = deriveDecoder
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
    case class SyncInfo(catchingUp: Boolean)

    implicit val syncInfoDecoder: Decoder[SyncInfo] =
      Decoder.forProduct1("catching_up")(SyncInfo.apply)

    implicit val nodeStatusDecoder: Decoder[NodeStatus] =
      Decoder.forProduct3("node_info", "sync_info", "validator_info")(NodeStatus.apply)

  }

  private[cometbft] case class CometBftQueryResult(response: CometBftQueryResponse)
  private[cometbft] case class CometBftQueryResponse(value: String)

  private[cometbft] case class CometBftBroadcastResult(code: Int, hash: String)

  case class CometBftError(message: String, responseCode: Int)
  case class QueryResponse(value: String)
}

class JavaExecutor(val ec: ExecutionContext) extends Executor {
  override def execute(command: Runnable): Unit = ec.execute(command)
}
