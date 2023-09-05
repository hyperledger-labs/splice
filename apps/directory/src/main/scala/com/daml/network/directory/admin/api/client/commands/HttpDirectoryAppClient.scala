package com.daml.network.directory.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.http.v0.directory as http
import com.daml.network.http.v0.external.{directory as externalHttp}
import com.daml.network.util.{Codec, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import cats.data.EitherT
import com.daml.network.http.v0.definitions

object HttpDirectoryAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.DirectoryClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.DirectoryClient.httpClient(
        HttpClientBuilder().buildClient,
        host,
      )
  }

  abstract class ExternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = externalHttp.DirectoryClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = externalHttp.DirectoryClient.httpClient(HttpClientBuilder().buildClient, host)
  }

  case class CreateDirectoryEntry(name: String, url: String, description: String)
      extends ExternalBaseCommand[
        externalHttp.CreateDirectoryEntryResponse,
        definitions.CreateDirectoryEntryResponse,
      ] {

    def submitRequest(client: externalHttp.DirectoryClient, headers: List[HttpHeader]): EitherT[
      Future,
      Either[Throwable, HttpResponse],
      externalHttp.CreateDirectoryEntryResponse,
    ] = {
      val request = definitions.CreateDirectoryEntryRequest(
        name,
        url,
        description,
      )
      client.createDirectoryEntry(request, headers = headers)
    }

    protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.CreateDirectoryEntryResponse.OK(res) =>
      Right(res)
    }
  }

  case class ListEntries(
      namePrefix: String,
      pageSize: Int,
  ) extends BaseCommand[http.ListEntriesResponse, Seq[
        Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]
      ]] {

    def submitRequest(client: Client, headers: List[HttpHeader]) =
      client.listEntries(Some(namePrefix), pageSize, headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListEntriesResponse.OK(response) =>
      response.entries
        .traverse(entry => Contract.fromHttp(codegen.DirectoryEntry.COMPANION)(entry))
        .leftMap(_.toString)
    }
  }

  case class LookupEntryByParty(
      party: PartyId
  ) extends BaseCommand[
        http.LookupEntryByPartyResponse,
        Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupEntryByParty(party.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.LookupEntryByPartyResponse.OK(response) =>
      for {
        entry <- Contract
          .fromHttp(codegen.DirectoryEntry.COMPANION)(response.entry)
          .leftMap(_.toString)
      } yield entry
    }
  }

  case class LookupEntryByName(
      name: String
  ) extends BaseCommand[
        http.LookupEntryByNameResponse,
        Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupEntryByName(name, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.LookupEntryByNameResponse.OK(response) =>
      for {
        entry <- Contract
          .fromHttp(codegen.DirectoryEntry.COMPANION)(response.entry)
          .leftMap(_.toString)
      } yield entry
    }
  }

  case class GetProviderPartyId() extends BaseCommand[http.GetProviderPartyIdResponse, PartyId] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.getProviderPartyId(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetProviderPartyIdResponse.OK(response) =>
        Codec.decode(Codec.Party)(response.providerPartyId)
    }
  }
}
