// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.

package com.daml.network.sv.cometbft

import java.net.http.HttpClient
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

import cats.data.EitherT
import cats.syntax.either.*
import com.daml.network.sv.cometbft.CometBftClient.CometBftCallResponse.{
  broadcastDecoder,
  queryDecoder,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.*

import scala.annotation.nowarn
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.{ExecutionContext, Future}

class CometBftClient(
    conf: CometBftConnectionConfig,
    override val loggerFactory: NamedLoggerFactory,
)(implicit executionContext: ExecutionContext)
    extends NamedLogging {
  import CometBftClient.*

  private val idCounter = new AtomicLong(1)

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

  def query(queryParams: String): EitherT[Future, QueryError, QueryResponse] = {
    callCometBftJsonHttp[CometBftQueryResult](AbciQueryMethod, queryParams).map(response =>
      QueryResponse(response.result.response.value)
    )
  }

  def send(
      message: Array[Byte]
  ): EitherT[Future, QueryError, Unit] = {
    val encodedRequest =
      Base64.getEncoder.encodeToString(message)
    callCometBftJsonHttp[CometBftBroadcastResult](
      "broadcast_tx_async",
      s"""{"tx" : "$encodedRequest"}""",
    ).map(_ => {})
  }

  private def callCometBftJsonHttp[T: Decoder](
      method: String,
      param: String,
  ): EitherT[Future, QueryError, CometBftCallResponse[T]] = EitherT {
    val toSend =
      s"""{
         |  "jsonrpc": "2.0",
         |  "method": "$method",
         |  "params": $param,
         |  "id": ${idCounter.incrementAndGet()}
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
            QueryError(
              s"Failed to decode response: ${decodeError.toString}. Full response: $responseBody",
              200,
            )
          )
        } else {
          decode[CometBftErrorResponse](responseBody)
            .fold(
              err =>
                QueryError(
                  s"Failed to parse json response: $err. Original body response: $responseBody",
                  response.statusCode(),
                ),
              res => QueryError(res.error, response.statusCode()),
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

  private def encodeURI(protocol: String = "http", endpoint: String = "") =
    java.net.URI.create(
      s"$protocol://${conf.cometbftNodeHost}:${conf.cometbftNodePort}$endpoint"
    )

}

@nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
object CometBftClient {

  private val AbciQueryMethod = "abci_query"
  private[cometbft] case class CometBftErrorResponse(error: String, id: Int)

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
  private[cometbft] case class CometBftQueryResult(response: CometBftQueryResponse)
  private[cometbft] case class CometBftQueryResponse(value: String)

  private[cometbft] case class CometBftBroadcastResult(code: Int, hash: String)

  case class QueryError(message: String, responseCode: Int)
  case class QueryResponse(value: String)
}

class JavaExecutor(val ec: ExecutionContext) extends Executor {
  override def execute(command: Runnable): Unit = ec.execute(command)
}
