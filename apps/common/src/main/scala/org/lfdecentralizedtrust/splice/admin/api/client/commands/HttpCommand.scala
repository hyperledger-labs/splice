// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.http.HttpClient
import io.circe.parser.*

import scala.concurrent.{ExecutionContext, Future}
import org.lfdecentralizedtrust.splice.http.v0.definitions.ErrorResponse
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.tracing.TraceContext

case class HttpCommandException(
    request: HttpRequest,
    status: StatusCode,
    responseBody: HttpCommandException.ResponseBody,
) extends Exception(
      s"HTTP ${status} ${request.method.name} at '${request.uri.path.toString}' on ${request.uri.authority}. Command failed, message: ${responseBody.message}"
    ) {
  def message: String = responseBody.message
}

object HttpCommandException {
  sealed trait ResponseBody {
    def message: String
  }
  case class ErrorResponseBody(errorResponse: ErrorResponse) extends ResponseBody {
    override def message: String = errorResponse.error
  }
  case class RawResponse(body: String) extends ResponseBody {
    override def message: String = body
  }
}

/** Equivalent of Canton’s AdminCommand but for our
  * native HTTP APIs.
  */
trait HttpCommand[Res, Result] {
  lazy val commandName: String = {
    // not using getClass.getSimpleName because it ignores the hierarchy of nested classes, and it also throws unexpected exceptions
    getClass.getName.split('.').last.replace("$", ".")
  }

  type Client

  def createClient(host: String, clientName: String)(implicit
      httpClient: HttpClient,
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Client

  def submitRequest(
      client: Client,
      headers: List[HttpHeader],
  ): EitherT[Future, Either[Throwable, HttpResponse], Res]

  final def handleResponse(
      response: Res
  )(implicit decoder: TemplateJsonDecoder): Either[String, Result] = {
    this.handleOk().apply(response)
  }

  // Narrow down clients to only focusing on 200-OK responses; non-successful API responses are caught in the client
  protected def handleOk()(implicit
      decoder: TemplateJsonDecoder
  ): PartialFunction[Res, Either[String, Result]]

  private[splice] final def withRawResponse
      : HttpCommand[Res, Res] { type Client = HttpCommand.this.Client } = {
    val self: this.type = this
    new HttpCommand[Res, Res] {
      type Client = self.Client

      override def createClient(host: String, clientName: String)(implicit
          httpClient: HttpClient,
          tc: TraceContext,
          ec: ExecutionContext,
          mat: Materializer,
      ) = self.createClient(host, clientName)

      override def submitRequest(
          client: Client,
          headers: List[HttpHeader],
      ) = self.submitRequest(client, headers)

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ) = { case res => Right(res) }

      override lazy val commandName: String = self.commandName
    }
  }
}

object HttpClientBuilder {
  def apply()(implicit
      httpClient: HttpClient,
      ec: ExecutionContext,
      mat: Materializer,
  ) =
    new HttpClientBuilder()
}

final class HttpClientBuilder()(implicit
    httpClient: HttpClient,
    ec: ExecutionContext,
    mat: Materializer,
) {
  private def getApiErrorFromResponse(
      request: HttpRequest,
      response: HttpResponse,
  ): Future[HttpCommandException] = {
    Unmarshal(response)
      .to[String]
      .map { body =>
        val decoded = for {
          parsed <- parse(body)
          errorResponse <- parsed.as[ErrorResponse]
        } yield errorResponse

        // Fallback to original response string if deserializing to ErrorResponse fails
        decoded.fold[HttpCommandException.ResponseBody](
          _ => HttpCommandException.RawResponse(body),
          HttpCommandException.ErrorResponseBody(_),
        )
      }
      .map { errorMessage => HttpCommandException(request, response.status, errorMessage) }
  }

  private def httpClientWithErrors(
      nextClient: HttpRequest => Future[HttpResponse],
      errors: PartialFunction[StatusCode, Unit],
  )(
      req: HttpRequest
  ) = {
    nextClient(req).flatMap { _resp =>
      errors
        .andThen(_ =>
          getApiErrorFromResponse(req, _resp).flatMap { error =>
            Future.failed[HttpResponse](error)
          }
        )
        .applyOrElse(_resp.status, (_: StatusCode) => Future.successful(_resp))
    }
  }

  def buildClient(
      clientName: String,
      operationName: String,
      nonErrorStatusCode: Set[StatusCode] = Set.empty,
  ): HttpRequest => Future[HttpResponse] = {
    httpClientWithErrors(
      httpClient.executeRequest(clientName, operationName),
      {
        case code @ (StatusCodes.ServerError(_) | StatusCodes.ClientError(_))
            if !nonErrorStatusCode.contains(code) =>
      },
    )
  }
}
