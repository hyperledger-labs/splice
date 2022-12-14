// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.admin.api.client

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent.{ExecutionContext, Future}

/** HTTP Variant of Canton’s GrpcCtlRunner.
  * Canton also has an HttpCtlRunner but it’s written
  * against sttp whereas we need akka-http since that
  * is what guardrail generates for our openapi schemas.
  */
class HttpCtlRunner(
    val loggerFactory: NamedLoggerFactory
) extends NamedLogging {

  def run[Res, Result](
      instanceName: String,
      host: String,
      command: HttpCommand[Res, Result],
  )(implicit
      templateDecoder: TemplateJsonDecoder,
      httpClient: HttpRequest => Future[HttpResponse],
      ec: ExecutionContext,
      mat: Materializer,
  ): EitherT[Future, String, Result] = {

    val client: command.Client = command
      .createClient(host)

    for {
      response <- command.submitRequest(client).leftMap(_.toString)
      result <- EitherT.fromEither[Future](command.handleResponse(response))
    } yield result
  }
}
