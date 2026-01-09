// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommand
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as codegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.SubscriptionRequest
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.external.ans as externalHttp
import org.lfdecentralizedtrust.splice.util.{Codec, TemplateJsonDecoder}

import scala.concurrent.Future

object HttpAnsAppClient {
  import externalHttp.AnsClient as Client
  abstract class ExternalBaseCommand[Res, Result] extends HttpCommand[Res, Result, Client] {
    val createGenClientFn = (fn, host, ec, mat) => Client.httpClient(fn, host)(ec, mat)
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
