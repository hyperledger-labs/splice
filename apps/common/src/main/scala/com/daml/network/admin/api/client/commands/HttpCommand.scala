// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.admin.api.client.commands

import akka.http.scaladsl.model.*
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import cats.data.EitherT
import io.circe.parser.*

import scala.concurrent.{ExecutionContext, Future}

import com.daml.network.http.v0.definitions.ErrorResponse
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.tracing.TraceContext

case class HttpCommandException(status: StatusCode, message: String) extends Exception {
  override def toString(): String = s"HttpCommandException (status: ${status}, message: ${message})"
}

/** Equivalent of Canton’s AdminCommand but for our
  * native HTTP APIs.
  */
trait HttpCommand[Res, Result] {

  type Client

  def createClient(host: String)(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Client

  def submitRequest(
      client: Client,
      headers: List[HttpHeader],
  ): EitherT[Future, Either[Throwable, HttpResponse], Res]

  def handleResponse(
      response: Res
  )(implicit decoder: TemplateJsonDecoder): Either[String, Result] = {
    this.handleOk().apply(response)
  }

  // Narrow down clients to only focusing on 200-OK responses; non-successful API responses are caught in the client
  protected def handleOk()(implicit
      decoder: TemplateJsonDecoder
  ): PartialFunction[Res, Either[String, Result]]

  def fullName: String =
    // not using getClass.getSimpleName because it ignores the hierarchy of nested classes, and it also throws unexpected exceptions
    getClass.getName.split('.').last.replace("$", ".")

}

object HttpClientBuilder {
  def apply()(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      ec: ExecutionContext,
      mat: Materializer,
  ) =
    new HttpClientBuilder()
}

final class HttpClientBuilder()(implicit
    httpClient: HttpRequest => Future[HttpResponse],
    ec: ExecutionContext,
    mat: Materializer,
) {
  private def getApiErrorFromResponse(response: HttpResponse): Future[HttpCommandException] = {
    Unmarshal(response)
      .to[String]
      .map { body =>
        val decoded = for {
          parsed <- parse(body)
          errorResponse <- parsed.as[ErrorResponse]
        } yield errorResponse.error

        // Fallback to original response string if deserializing to ErrorResponse fails
        decoded.getOrElse(body)
      }
      .map { errorMessage => HttpCommandException(response.status, errorMessage) }
  }

  def httpClientWithErrors(nextClient: HttpRequest => Future[HttpResponse])(
      req: HttpRequest
  ) = {
    nextClient(req).flatMap { _resp =>
      _resp.status match {
        case StatusCodes.ServerError(_) | StatusCodes.ClientError(_) =>
          getApiErrorFromResponse(_resp).flatMap { error =>
            Future.failed(error)
          }
        case _ => Future.successful(_resp)
      }
    }
  }

  def buildClient: HttpRequest => Future[HttpResponse] = {
    httpClientWithErrors(httpClient)
  }
}
