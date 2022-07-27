package com.daml.network.directory.provider.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.Primitive
import com.daml.network.directory.provider.{DirectoryEntryRequest, DirectoryInstallRequest}
import com.daml.network.directory_provider.v0
import com.daml.network.directory_provider.v0.DirectoryProviderServiceGrpc.DirectoryProviderServiceStub
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CN.{Directory => codegen, Wallet => walletCodegen}
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object DirectoryProviderCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = DirectoryProviderServiceStub
    override def createService(channel: ManagedChannel): DirectoryProviderServiceStub =
      v0.DirectoryProviderServiceGrpc.stub(channel)
  }

  case class ListInstallRequests()
      extends BaseCommand[Empty, v0.ListInstallRequestsResponse, Seq[DirectoryInstallRequest]] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: DirectoryProviderServiceStub,
        request: Empty,
    ): Future[v0.ListInstallRequestsResponse] = service.listInstallRequests(request)

    override def handleResponse(
        response: v0.ListInstallRequestsResponse
    ): Either[String, Seq[DirectoryInstallRequest]] =
      response.installRequests
        .traverse(request => DirectoryInstallRequest.fromProto(request))
        .leftMap(_.toString)
  }

  case class AcceptInstallRequest(
      contractId: Primitive.ContractId[codegen.DirectoryInstallRequest],
      svc: PartyId,
  ) extends BaseCommand[
        v0.AcceptInstallRequestRequest,
        v0.AcceptInstallRequestResponse,
        Primitive.ContractId[codegen.DirectoryInstall],
      ] {

    override def createRequest(): Either[String, v0.AcceptInstallRequestRequest] =
      Right(
        v0.AcceptInstallRequestRequest(ApiTypes.ContractId.unwrap(contractId), svc.toProtoPrimitive)
      )

    override def submitRequest(
        service: DirectoryProviderServiceStub,
        request: v0.AcceptInstallRequestRequest,
    ): Future[v0.AcceptInstallRequestResponse] = service.acceptInstallRequest(request)

    override def handleResponse(
        response: v0.AcceptInstallRequestResponse
    ): Either[String, Primitive.ContractId[codegen.DirectoryInstall]] =
      Right(Primitive.ContractId[codegen.DirectoryInstall](response.contractId))
  }

  case class ListEntryRequests(
  ) extends BaseCommand[
        Empty,
        v0.ListEntryRequestsResponse,
        Seq[DirectoryEntryRequest],
      ] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: DirectoryProviderServiceStub,
        request: Empty,
    ): Future[v0.ListEntryRequestsResponse] = service.listEntryRequests(request)

    override def handleResponse(
        response: v0.ListEntryRequestsResponse
    ): Either[String, Seq[DirectoryEntryRequest]] =
      response.entryRequests
        .traverse(request => DirectoryEntryRequest.fromProto(request))
        .leftMap(_.toString)
  }

  case class RequestEntryPayment(
      contractId: Primitive.ContractId[codegen.DirectoryEntryRequest]
  ) extends BaseCommand[
        v0.RequestEntryPaymentRequest,
        v0.RequestEntryPaymentResponse,
        Primitive.ContractId[walletCodegen.PaymentRequest.PaymentRequest],
      ] {

    override def createRequest(): Either[String, v0.RequestEntryPaymentRequest] =
      Right(
        v0.RequestEntryPaymentRequest(ApiTypes.ContractId.unwrap(contractId))
      )

    override def submitRequest(
        service: DirectoryProviderServiceStub,
        request: v0.RequestEntryPaymentRequest,
    ): Future[v0.RequestEntryPaymentResponse] = service.requestEntryPayment(request)

    override def handleResponse(
        response: v0.RequestEntryPaymentResponse
    ): Either[String, Primitive.ContractId[walletCodegen.PaymentRequest.PaymentRequest]] =
      Right(Primitive.ContractId[walletCodegen.PaymentRequest.PaymentRequest](response.contractId))
  }

  case class CollectEntryPayment(
      contractId: Primitive.ContractId[walletCodegen.PaymentRequest.ApprovedPayment]
  ) extends BaseCommand[
        v0.CollectEntryPaymentRequest,
        v0.CollectEntryPaymentResponse,
        Primitive.ContractId[codegen.DirectoryEntry],
      ] {

    override def createRequest(): Either[String, v0.CollectEntryPaymentRequest] =
      Right(
        v0.CollectEntryPaymentRequest(ApiTypes.ContractId.unwrap(contractId))
      )

    override def submitRequest(
        service: DirectoryProviderServiceStub,
        request: v0.CollectEntryPaymentRequest,
    ): Future[v0.CollectEntryPaymentResponse] = service.collectEntryPayment(request)

    override def handleResponse(
        response: v0.CollectEntryPaymentResponse
    ): Either[String, Primitive.ContractId[codegen.DirectoryEntry]] =
      Right(Primitive.ContractId[codegen.DirectoryEntry](response.contractId))
  }
}
