// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as codegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.SubscriptionRequest
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.external.ans as externalHttp
import org.lfdecentralizedtrust.splice.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

object HttpAnsAppClient {

  abstract class ExternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = externalHttp.AnsClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = externalHttp.AnsClient.httpClient(HttpClientBuilder().buildClient(), host)
  }

  case class CreateAnsEntryResponse(
      entryContextCid: codegen.AnsEntryContext.ContractId,
      subscriptionRequestCid: SubscriptionRequest.ContractId,
      name: String,
      url: String,
      description: String,
  )
  case class CreateAnsEntry(name: String, url: String, description: String)
      extends ExternalBaseCommand[
        externalHttp.CreateAnsEntryResponse,
        CreateAnsEntryResponse,
      ] {

    def submitRequest(client: externalHttp.AnsClient, headers: List[HttpHeader]): EitherT[
      Future,
      Either[Throwable, HttpResponse],
      externalHttp.CreateAnsEntryResponse,
    ] = {
      val request = definitions.CreateAnsEntryRequest(
        name,
        url,
        description,
      )
      client.createAnsEntry(request, headers = headers)
    }

    protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.CreateAnsEntryResponse.OK(res) =>
      for {
        entryContextCid <- Codec.decodeJavaContractId(codegen.AnsEntryContext.COMPANION)(
          res.entryContextCid
        )
        subscriptionRequestCid <- Codec.decodeJavaContractId(SubscriptionRequest.COMPANION)(
          res.subscriptionRequestCid
        )
        response = CreateAnsEntryResponse(
          entryContextCid,
          subscriptionRequestCid,
          res.name,
          res.url,
          res.description,
        )
      } yield response
    }
  }

  case class ListAnsEntries()
      extends ExternalBaseCommand[
        externalHttp.ListAnsEntriesResponse,
        definitions.ListAnsEntriesResponse,
      ] {

    override def submitRequest(
        client: externalHttp.AnsClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.ListAnsEntriesResponse] =
      client.listAnsEntries(headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[externalHttp.ListAnsEntriesResponse, Either[
      String,
      definitions.ListAnsEntriesResponse,
    ]] = { case externalHttp.ListAnsEntriesResponse.OK(res) =>
      Right(res)
    }
  }
}
