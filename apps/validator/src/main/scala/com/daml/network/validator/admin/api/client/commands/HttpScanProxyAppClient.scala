// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.admin.api.client.commands

import cats.data.EitherT
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.splice.ans.AnsRules
import com.daml.network.http.HttpClient
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import com.daml.network.http.v0.{definitions, scanproxy as scanProxy}
import com.daml.network.http.v0.scanproxy.{GetDsoPartyIdResponse, ScanproxyClient}
import com.daml.network.util.{ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

object HttpScanProxyAppClient {

  abstract class ScanProxyBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = scanProxy.ScanproxyClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = scanProxy.ScanproxyClient.httpClient(HttpClientBuilder().buildClient(), host)
  }

  case object GetDsoParty extends ScanProxyBaseCommand[scanProxy.GetDsoPartyIdResponse, PartyId] {
    override def submitRequest(
        client: ScanproxyClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], GetDsoPartyIdResponse] =
      client.getDsoPartyId(headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[GetDsoPartyIdResponse, Either[String, PartyId]] = {
      case scanProxy.GetDsoPartyIdResponse.OK(response) =>
        Right(PartyId.tryFromProtoPrimitive(response.dsoPartyId))
    }
  }

  case object GetAnsRules
      extends ScanProxyBaseCommand[
        scanProxy.GetAnsRulesResponse,
        ContractWithState[AnsRules.ContractId, AnsRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], scanProxy.GetAnsRulesResponse] = {
      client.getAnsRules(
        definitions.GetAnsRulesRequest(None, None),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case scanProxy.GetAnsRulesResponse.OK(response) =>
        for {
          ansRules <- ContractWithState.handleMaybeCached(AnsRules.COMPANION)(
            None,
            response.ansRulesUpdate,
          )
        } yield ansRules
    }
  }
}
