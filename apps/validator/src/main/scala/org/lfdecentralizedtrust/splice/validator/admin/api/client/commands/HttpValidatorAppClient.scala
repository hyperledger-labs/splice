// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommand
import org.lfdecentralizedtrust.splice.http.v0.validator as http
import org.lfdecentralizedtrust.splice.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.Future

object HttpValidatorAppClient {
  import http.ValidatorClient as Client
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result, Client] {
    val createGenClientFn = (fn, host, ec, mat) => Client.httpClient(fn, host)(ec, mat)
  }

  case object Register extends BaseCommand[http.RegisterResponse, PartyId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.RegisterResponse] =
      client.register(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.RegisterResponse.OK(response) =>
      Codec.decode(Codec.Party)(response.partyId)
    }
  }
}
