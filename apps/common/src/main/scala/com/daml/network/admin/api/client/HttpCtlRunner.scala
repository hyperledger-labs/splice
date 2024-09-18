// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.admin.api.client

import org.apache.pekko.http.scaladsl.model.HttpHeader
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.admin.api.client.TraceContextPropagation.*
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.admin.api.client.commands.HttpCommandException
import com.daml.network.http.HttpClient

/** HTTP Variant of Canton’s GrpcCtlRunner.
  * Canton also has an HttpCtlRunner but it’s written
  * against sttp whereas we need akka-http since that
  * is what guardrail generates for our openapi schemas.
  */
class HttpCtlRunner(
    val loggerFactory: NamedLoggerFactory
) extends NamedLogging {

  def run[Res, Result](
      host: String,
      command: HttpCommand[Res, Result],
      headers: List[HttpHeader],
  )(implicit
      templateDecoder: TemplateJsonDecoder,
      httpClient: HttpClient,
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): EitherT[Future, String, Result] = {

    val client: command.Client = command.createClient(host)

    for {
      response <- command
        .submitRequest(client, tc.propagate(headers))
        .leftMap(resp =>
          resp match {
            case Left(httpErr: HttpCommandException) => throw httpErr
            case err => err.toString()
          }
        )
      result <- EitherT.fromEither[Future](command.handleResponse(response))
    } yield result
  }
}
