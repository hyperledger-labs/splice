// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import org.apache.pekko.stream.Materializer
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpClientBuilder
import org.lfdecentralizedtrust.splice.environment.BaseAppConnection
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.json_api_public as v0
import org.lfdecentralizedtrust.splice.validator.config.AppManagerConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.canton.util.EitherTUtil

import scala.concurrent.{ExecutionContext, Future}
import io.circe.Json

class HttpJsonApiPublicHandler(
    config: AppManagerConfig,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    httpClient: HttpClient,
    mat: Materializer,
) extends v0.JsonApiPublicHandler[Unit]
    with Spanning
    with NamedLogging {

  // Reverse proxy for JSON API to add CORS headers.
  // The endpoints here are public as the underlying participant does the actual JWT check.

  object JsonApiProxy {
    def apply(
        httpClient: HttpRequest => Future[HttpResponse],
        host: String,
    )(implicit ec: ExecutionContext, mat: Materializer): JsonApiProxy =
      new JsonApiProxy(host = host)(httpClient = httpClient, ec = ec, mat = mat)
  }
  class JsonApiProxy(host: String)(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      ec: ExecutionContext,
      mat: Materializer,
  ) extends v0.JsonApiPublicClient(host) {
    // basePath for the actual json API should be empty, i.e. we remove the prefix from the validator API url (/api/validator/jsonApiProxy) before forwarding.
    override val basePath = ""
  }

  val jsonApiClient = JsonApiProxy(
    HttpClientBuilder().buildClient(),
    config.jsonApiUrl.toString,
  )

  def handleResponse[R](response: EitherT[Future, Either[Throwable, HttpResponse], R]): Future[R] =
    EitherTUtil.toFuture(response.leftMap[Throwable] {
      case Left(throwable) => throwable
      case Right(response) => new BaseAppConnection.UnexpectedHttpResponse(response)
    })

  def jsonApiCreate(
      respond: v0.JsonApiPublicResource.JsonApiCreateResponse.type
  )(body: Json, authorization: String)(
      extracted: Unit
  ): Future[v0.JsonApiPublicResource.JsonApiCreateResponse] =
    handleResponse(
      jsonApiClient.jsonApiCreate(
        body,
        authorization,
      )
    ).map(_.fold(v0.JsonApiPublicResource.JsonApiCreateResponse.OK(_)))
  def jsonApiExercise(
      respond: v0.JsonApiPublicResource.JsonApiExerciseResponse.type
  )(body: Json, authorization: String)(
      extracted: Unit
  ): Future[v0.JsonApiPublicResource.JsonApiExerciseResponse] =
    handleResponse(
      jsonApiClient.jsonApiExercise(
        body,
        authorization,
      )
    ).map(_.fold(v0.JsonApiPublicResource.JsonApiExerciseResponse.OK(_)))
  def jsonApiQuery(
      respond: v0.JsonApiPublicResource.JsonApiQueryResponse.type
  )(body: Json, authorization: String)(
      extracted: Unit
  ): Future[v0.JsonApiPublicResource.JsonApiQueryResponse] =
    handleResponse(
      jsonApiClient.jsonApiQuery(
        body,
        authorization,
      )
    ).map(_.fold(v0.JsonApiPublicResource.JsonApiQueryResponse.OK(_)))
  def jsonApiUser(
      respond: v0.JsonApiPublicResource.JsonApiUserResponse.type
  )(body: Json, authorization: String)(
      extracted: Unit
  ): Future[v0.JsonApiPublicResource.JsonApiUserResponse] =
    handleResponse(
      jsonApiClient.jsonApiUser(
        body,
        authorization,
      )
    ).map(_.fold(v0.JsonApiPublicResource.JsonApiUserResponse.OK(_)))

}
