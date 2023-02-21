package com.daml.network.directory.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.http.v0.directory as http
import com.daml.network.util.{Contract, Proto, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

object HttpDirectoryAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.DirectoryClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.DirectoryClient(host)
  }

  case class ListEntries(
      namePrefix: String,
      pageSize: Int,
  ) extends BaseCommand[http.ListEntriesResponse, Seq[
        Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]
      ]] {

    def submitRequest(client: Client, headers: List[HttpHeader]) =
      client.listEntries(Some(namePrefix), pageSize)

    override def handleResponse(
        response: http.ListEntriesResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]]] =
      response match {
        case http.ListEntriesResponse.OK(response) =>
          response.entries
            .traverse(entry => Contract.fromJson(codegen.DirectoryEntry.COMPANION)(entry))
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
    ) = client.lookupEntryByParty(party.toProtoPrimitive)

    override def handleResponse(
        response: http.LookupEntryByPartyResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]] = {
      response match {
        case http.LookupEntryByPartyResponse.OK(response) =>
          val r = for {
            entry <- Contract.fromJson(codegen.DirectoryEntry.COMPANION)(response.entry)
          } yield entry
          r.leftMap(_.toString)
        case http.LookupEntryByPartyResponse.NotFound =>
          Left(s"No directory entry found for party ${party.toProtoPrimitive}")
      }
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
    ) = client.lookupEntryByName(name)

    override def handleResponse(
        response: http.LookupEntryByNameResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]] =
      response match {
        case http.LookupEntryByNameResponse.OK(response) =>
          val r = for {
            entry <- Contract.fromJson(codegen.DirectoryEntry.COMPANION)(response.entry)
          } yield entry
          r.leftMap(_.toString)
        case http.LookupEntryByNameResponse.NotFound =>
          Left(s"No directory entry found for name $name")
      }
  }

  case class GetProviderPartyId() extends BaseCommand[http.GetProviderPartyIdResponse, PartyId] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.getProviderPartyId()

    override def handleResponse(
        response: http.GetProviderPartyIdResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, PartyId] = response match {
      case http.GetProviderPartyIdResponse.OK(response) =>
        Proto.decode(Proto.Party)(response.providerPartyId)
    }
  }
}
