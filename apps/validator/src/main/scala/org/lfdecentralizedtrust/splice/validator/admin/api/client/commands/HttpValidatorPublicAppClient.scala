// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.validator_public as http
import org.lfdecentralizedtrust.splice.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

final case class UserInfo(
    primaryParty: PartyId,
    userName: String,
    featured: Boolean,
)

object HttpValidatorPublicAppClient {
  val clientName = "HttpValidatorPublicAppClient"

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ValidatorPublicClient

    def createClient(host: String, clientName: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ValidatorPublicClient.httpClient(
        HttpClientBuilder().buildClient(clientName, commandName),
        host,
      )
  }

  case object GetValidatorUserInfo
      extends BaseCommand[http.GetValidatorUserInfoResponse, UserInfo] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetValidatorUserInfoResponse] =
      client.getValidatorUserInfo(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetValidatorUserInfoResponse.OK(response) =>
      Codec
        .decode(Codec.Party)(response.partyId)
        .map(pid => UserInfo(pid, response.userName, response.featured))
    }
  }
}
