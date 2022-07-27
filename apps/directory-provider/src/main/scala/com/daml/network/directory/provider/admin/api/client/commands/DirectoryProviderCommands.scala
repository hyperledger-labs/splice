package com.daml.network.directory.provider.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.Primitive
import com.daml.network.directory.provider.DirectoryInstallRequest
import com.daml.network.directory_provider.v0
import com.daml.network.directory_provider.v0.DirectoryProviderServiceGrpc.DirectoryProviderServiceStub
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CN.{Directory => codegen}
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
}
