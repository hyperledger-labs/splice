// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import cats.data.EitherT
import cats.syntax.either.*
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.http.HttpClient
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes}
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.http.v0.{definitions, scanproxy as scanProxy}
import org.lfdecentralizedtrust.splice.http.v0.scanproxy.{GetDsoPartyIdResponse, ScanproxyClient}
import org.lfdecentralizedtrust.splice.util.{Codec, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.TransferPreapproval
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.TransferCommandCounter

import scala.concurrent.{ExecutionContext, Future}

object HttpScanProxyAppClient {
  val clientName = "HttpScanProxyAppClient"
  abstract class ScanProxyBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = scanProxy.ScanproxyClient

    def createClient(host: String, clientName: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      scanProxy.ScanproxyClient.httpClient(
        HttpClientBuilder().buildClient(clientName, commandName, Set(StatusCodes.NotFound)),
        host,
      )
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

  case object GetDsoInfo
      extends ScanProxyBaseCommand[scanProxy.GetDsoInfoResponse, definitions.GetDsoInfoResponse] {
    override def submitRequest(
        client: ScanproxyClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], scanProxy.GetDsoInfoResponse] =
      client.getDsoInfo(headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[scanProxy.GetDsoInfoResponse, Either[
      String,
      definitions.GetDsoInfoResponse,
    ]] = { case scanProxy.GetDsoInfoResponse.OK(response) =>
      Right(response)
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

  case class LookupTransferPreapprovalByParty(party: PartyId)
      extends ScanProxyBaseCommand[scanProxy.LookupTransferPreapprovalByPartyResponse, Option[
        ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.lookupTransferPreapprovalByParty(party.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case scanProxy.LookupTransferPreapprovalByPartyResponse.OK(response) =>
        ContractWithState
          .fromHttp(TransferPreapproval.COMPANION)(response.transferPreapproval)
          .map(Some(_))
          .leftMap(_.toString)
      case scanProxy.LookupTransferPreapprovalByPartyResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class LookupTransferCommandCounterByParty(party: PartyId)
      extends ScanProxyBaseCommand[scanProxy.LookupTransferCommandCounterByPartyResponse, Option[
        ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupTransferCommandCounterByParty(party.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case scanProxy.LookupTransferCommandCounterByPartyResponse.OK(response) =>
        ContractWithState
          .fromHttp(TransferCommandCounter.COMPANION)(response.transferCommandCounter)
          .map(Some(_))
          .leftMap(_.toString)
      case scanProxy.LookupTransferCommandCounterByPartyResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class LookupTransferCommandStatus(
      sender: PartyId,
      nonce: Long,
  ) extends ScanProxyBaseCommand[scanProxy.LookupTransferCommandStatusResponse, Option[
        definitions.LookupTransferCommandStatusResponse
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupTransferCommandStatus(Codec.encode(sender), nonce, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case scanProxy.LookupTransferCommandStatusResponse.OK(ev) =>
        Right(Some(ev))
      case scanProxy.LookupTransferCommandStatusResponse.NotFound(_) =>
        Right(None)
    }
  }

}
