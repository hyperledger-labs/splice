package com.daml.network.directory.provider.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.network.directory.provider.DirectoryInstallRequest
import com.daml.network.directory_provider.v0
import com.daml.network.directory_provider.v0.DirectoryProviderServiceGrpc.DirectoryProviderServiceStub
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
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
}
