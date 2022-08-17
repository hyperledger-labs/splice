package com.daml.network.directory.user.admin.api.client.commands
import com.daml.ledger.client.binding.Primitive
import com.daml.network.directory_user.v0
import com.daml.network.directory_user.v0.DirectoryUserServiceGrpc.DirectoryUserServiceStub
import com.daml.network.util.Proto
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.network.CN.{Directory => codegen}
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object DirectoryUserCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = DirectoryUserServiceStub
    override def createService(channel: ManagedChannel): DirectoryUserServiceStub =
      v0.DirectoryUserServiceGrpc.stub(channel)
  }

  final case class RequestDirectoryInstall()
      extends BaseCommand[Empty, v0.RequestDirectoryInstallResponse, Primitive.ContractId[
        codegen.DirectoryInstallRequest
      ]] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())
    override def submitRequest(
        service: DirectoryUserServiceStub,
        request: Empty,
    ): Future[v0.RequestDirectoryInstallResponse] =
      service.requestDirectoryInstall(request)

    override def handleResponse(
        response: v0.RequestDirectoryInstallResponse
    ): Either[String, Primitive.ContractId[codegen.DirectoryInstallRequest]] =
      Proto.decodeContractId(response.contractId)
  }

  final case class RequestDirectoryEntry(name: String)
      extends BaseCommand[
        v0.RequestDirectoryEntryRequest,
        v0.RequestDirectoryEntryResponse,
        Primitive.ContractId[
          codegen.DirectoryEntryRequest
        ],
      ] {
    override def createRequest(): Either[String, v0.RequestDirectoryEntryRequest] =
      Right(v0.RequestDirectoryEntryRequest(name))
    override def submitRequest(
        service: DirectoryUserServiceStub,
        request: v0.RequestDirectoryEntryRequest,
    ): Future[v0.RequestDirectoryEntryResponse] =
      service.requestDirectoryEntry(request)

    override def handleResponse(
        response: v0.RequestDirectoryEntryResponse
    ): Either[String, Primitive.ContractId[codegen.DirectoryEntryRequest]] =
      Proto.decodeContractId(response.contractId)
  }
}
