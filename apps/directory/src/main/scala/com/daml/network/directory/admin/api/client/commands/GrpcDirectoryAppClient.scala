package com.daml.network.directory.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.network.codegen.java.cn.{directory => codegen}
import com.daml.network.directory.v0
import com.daml.network.directory.v0.DirectoryServiceGrpc.DirectoryServiceStub
import com.daml.network.util.{JavaContract as Contract, Proto}
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcDirectoryAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = DirectoryServiceStub

    override def createService(channel: ManagedChannel): DirectoryServiceStub =
      v0.DirectoryServiceGrpc.stub(channel)
  }

  case class ListEntries(
      namePrefix: String,
      pageSize: Int,
  ) extends BaseCommand[
        v0.ListEntriesRequest,
        v0.ListEntriesResponse,
        Seq[Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]],
      ] {

    override def createRequest(): Either[String, v0.ListEntriesRequest] =
      Right(v0.ListEntriesRequest(namePrefix, pageSize))

    override def submitRequest(
        service: DirectoryServiceStub,
        request: v0.ListEntriesRequest,
    ): Future[v0.ListEntriesResponse] = service.listEntries(request)

    override def handleResponse(
        response: v0.ListEntriesResponse
    ): Either[String, Seq[Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]]] =
      response.entries
        .traverse(entry => Contract.fromProto(codegen.DirectoryEntry.COMPANION)(entry))
        .leftMap(_.toString)
  }

  case class LookupEntryByParty(
      party: PartyId
  ) extends BaseCommand[
        v0.LookupEntryByPartyRequest,
        v0.LookupEntryByPartyResponse,
        Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry],
      ] {

    override def createRequest(): Either[String, v0.LookupEntryByPartyRequest] =
      Right(
        v0.LookupEntryByPartyRequest(party.toProtoPrimitive)
      )

    override def submitRequest(
        service: DirectoryServiceStub,
        request: v0.LookupEntryByPartyRequest,
    ): Future[v0.LookupEntryByPartyResponse] = service.lookupEntryByParty(request)

    override def handleResponse(
        response: v0.LookupEntryByPartyResponse
    ): Either[String, Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]] = {
      val r = for {
        entryField <- ProtoConverter.required("entry", response.entry)
        entry <- Contract.fromProto(codegen.DirectoryEntry.COMPANION)(entryField)
      } yield entry
      r.leftMap(_.toString)

    }
  }

  case class LookupEntryByName(
      name: String
  ) extends BaseCommand[
        v0.LookupEntryByNameRequest,
        v0.LookupEntryByNameResponse,
        Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry],
      ] {

    override def createRequest(): Either[String, v0.LookupEntryByNameRequest] =
      Right(
        v0.LookupEntryByNameRequest(name)
      )

    override def submitRequest(
        service: DirectoryServiceStub,
        request: v0.LookupEntryByNameRequest,
    ): Future[v0.LookupEntryByNameResponse] = service.lookupEntryByName(request)

    override def handleResponse(
        response: v0.LookupEntryByNameResponse
    ): Either[String, Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]] = {
      val r = for {
        entryField <- ProtoConverter.required("entry", response.entry)
        entry <- Contract.fromProto(codegen.DirectoryEntry.COMPANION)(entryField)
      } yield entry
      r.leftMap(_.toString)

    }
  }

  case class GetProviderPartyId()
      extends BaseCommand[Empty, v0.GetProviderPartyIdResponse, PartyId] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: DirectoryServiceStub,
        request: Empty,
    ): Future[v0.GetProviderPartyIdResponse] =
      service.getProviderPartyId(request)

    override def handleResponse(response: v0.GetProviderPartyIdResponse): Either[String, PartyId] =
      Proto.decode(Proto.Party)(response.providerPartyId)
  }
}
