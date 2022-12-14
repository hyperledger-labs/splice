// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.admin.api.client.commands

import akka.http.scaladsl.model._
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand.{
  DefaultBoundedTimeout,
  TimeoutType,
}

import scala.concurrent.{ExecutionContext, Future}

/** Equivalent of Canton’s AdminCommand but for our
  * native HTTP APIs.
  */
trait HttpCommand[Res, Result] {

  type Client

  def createClient(host: String)(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      ec: ExecutionContext,
      mat: Materializer,
  ): Client

  def submitRequest(client: Client): EitherT[Future, Either[Throwable, HttpResponse], Res]

  def handleResponse(response: Res)(implicit decoder: TemplateJsonDecoder): Either[String, Result]

  def timeoutType: TimeoutType = DefaultBoundedTimeout

  def fullName: String =
    // not using getClass.getSimpleName because it ignores the hierarchy of nested classes, and it also throws unexpected exceptions
    getClass.getName.split('.').last.replace("$", ".")

}
