// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}
import org.lfdecentralizedtrust.splice.http.v0.definitions.ErrorResponse
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

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
trait HttpCommand[Res, Result, C] {
  type Client = C

  lazy val fullName: String = {
    // not using getClass.getSimpleName because it ignores the hierarchy of nested classes, and it also throws unexpected exceptions
    getClass.getName.split('.').last.replace("$", ".").stripSuffix(".")
  }

  lazy val clientName: String = {
    fullName.split('.').headOption.getOrElse("UnknownClient")
  }

  lazy val operationName: String = {
    fullName.split('.').lastOption.getOrElse("UnknownOperation")
  }

  def nonErrorStatusCodes: Set[StatusCode] = Set.empty

  /** Must return a constructor (function) for the specific guardrails HTTP client * */
  protected def createGenClientFn
      : (HttpRequest => Future[HttpResponse], String, ExecutionContext, Materializer) => Client

  def createClient(host: String)(implicit
      httpClient: HttpClient,
      ec: ExecutionContext,
      mat: Materializer,
  ): Client = createGenClientFn(httpClientFn(), host, ec, mat)

  def httpClientFn()(implicit
      httpClient: HttpClient,
      ec: ExecutionContext,
      mat: Materializer,
  ): HttpRequest => Future[HttpResponse] =
    HttpClient.createHttpFn(clientName, operationName, nonErrorStatusCodes)

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

  private[splice] final def withRawResponse: HttpCommand[Res, Res, C] = {
    val self: this.type = this
    new HttpCommand[Res, Res, C] {
      override val createGenClientFn = self.createGenClientFn
      override def submitRequest(
          client: Client,
          headers: List[HttpHeader],
      ) = self.submitRequest(client, headers)

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ) = { case res => Right(res) }

      override lazy val operationName: String = self.operationName
    }
  }
}
