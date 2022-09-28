package com.daml.network.directory.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.{Directory => codegen, Wallet => walletCodegen}
import com.daml.network.directory.v0
import com.daml.network.directory.v0.DirectoryServiceGrpc.DirectoryServiceStub
import com.daml.network.util.{Contract, Proto}
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

  case class LookupInstall(user: PartyId)
      extends BaseCommand[v0.LookupInstallRequest, v0.LookupInstallResponse, Option[
        Contract[codegen.DirectoryInstall]
      ]] {

    override def createRequest(): Either[String, v0.LookupInstallRequest] =
      Right(v0.LookupInstallRequest(Proto.encode(user)))

    override def submitRequest(
        service: DirectoryServiceStub,
        request: v0.LookupInstallRequest,
    ): Future[v0.LookupInstallResponse] = service.lookupInstall(request)

    override def handleResponse(
        response: v0.LookupInstallResponse
    ): Either[String, Option[Contract[codegen.DirectoryInstall]]] =
      response.install
        .traverse(request => Contract.fromProto(codegen.DirectoryInstall)(request))
        .leftMap(_.toString)
  }

  case class ListEntryRequests(
  ) extends BaseCommand[
        Empty,
        v0.ListEntryRequestsResponse,
        Seq[Contract[codegen.DirectoryEntryRequest]],
      ] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: DirectoryServiceStub,
        request: Empty,
    ): Future[v0.ListEntryRequestsResponse] = service.listEntryRequests(request)

    override def handleResponse(
        response: v0.ListEntryRequestsResponse
    ): Either[String, Seq[Contract[codegen.DirectoryEntryRequest]]] =
      response.entryRequests
        .traverse(request => Contract.fromProto(codegen.DirectoryEntryRequest)(request))
        .leftMap(_.toString)
  }

  case class RequestEntryPayment(
      contractId: Primitive.ContractId[codegen.DirectoryEntryRequest]
  ) extends BaseCommand[
        v0.RequestEntryPaymentRequest,
        v0.RequestEntryPaymentResponse,
        Primitive.ContractId[walletCodegen.AppPaymentRequest],
      ] {

    override def createRequest(): Either[String, v0.RequestEntryPaymentRequest] =
      Right(
        v0.RequestEntryPaymentRequest(Proto.encode(contractId))
      )

    override def submitRequest(
        service: DirectoryServiceStub,
        request: v0.RequestEntryPaymentRequest,
    ): Future[v0.RequestEntryPaymentResponse] = service.requestEntryPayment(request)

    override def handleResponse(
        response: v0.RequestEntryPaymentResponse
    ): Either[String, Primitive.ContractId[walletCodegen.AppPaymentRequest]] =
      Right(Primitive.ContractId[walletCodegen.AppPaymentRequest](response.contractId))
  }

  case class CollectEntryPayment(
      contractId: Primitive.ContractId[walletCodegen.AcceptedAppPayment]
  ) extends BaseCommand[
        v0.CollectEntryPaymentRequest,
        v0.CollectEntryPaymentResponse,
        Primitive.ContractId[codegen.DirectoryEntry],
      ] {

    override def createRequest(): Either[String, v0.CollectEntryPaymentRequest] =
      Right(
        v0.CollectEntryPaymentRequest(Proto.encode(contractId))
      )

    override def submitRequest(
        service: DirectoryServiceStub,
        request: v0.CollectEntryPaymentRequest,
    ): Future[v0.CollectEntryPaymentResponse] = service.collectEntryPayment(request)

    override def handleResponse(
        response: v0.CollectEntryPaymentResponse
    ): Either[String, Primitive.ContractId[codegen.DirectoryEntry]] =
      Proto.decodeContractId[codegen.DirectoryEntry](response.contractId)
  }

  case class ListEntries(
  ) extends BaseCommand[
        Empty,
        v0.ListEntriesResponse,
        Seq[Contract[codegen.DirectoryEntry]],
      ] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: DirectoryServiceStub,
        request: Empty,
    ): Future[v0.ListEntriesResponse] = service.listEntries(request)

    override def handleResponse(
        response: v0.ListEntriesResponse
    ): Either[String, Seq[Contract[codegen.DirectoryEntry]]] =
      response.entries
        .traverse(entry => Contract.fromProto(codegen.DirectoryEntry)(entry))
        .leftMap(_.toString)
  }

  case class LookupEntryByParty(
      party: PartyId
  ) extends BaseCommand[
        v0.LookupEntryByPartyRequest,
        v0.LookupEntryByPartyResponse,
        Contract[codegen.DirectoryEntry],
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
    ): Either[String, Contract[codegen.DirectoryEntry]] = {
      val r = for {
        entryField <- ProtoConverter.required("entry", response.entry)
        entry <- Contract.fromProto(codegen.DirectoryEntry)(entryField)
      } yield entry
      r.leftMap(_.toString)

    }
  }

  case class LookupEntryByName(
      name: String
  ) extends BaseCommand[
        v0.LookupEntryByNameRequest,
        v0.LookupEntryByNameResponse,
        Contract[codegen.DirectoryEntry],
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
    ): Either[String, Contract[codegen.DirectoryEntry]] = {
      val r = for {
        entryField <- ProtoConverter.required("entry", response.entry)
        entry <- Contract.fromProto(codegen.DirectoryEntry)(entryField)
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
