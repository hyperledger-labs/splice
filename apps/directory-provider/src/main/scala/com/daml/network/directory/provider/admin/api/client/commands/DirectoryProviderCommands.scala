package com.daml.network.directory.provider.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.network.examples.v0
import com.daml.network.examples.v0.DirectoryProviderServiceGrpc.DirectoryProviderServiceStub
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import io.grpc.ManagedChannel

import scala.concurrent.Future

object DirectoryProviderCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = DirectoryProviderServiceStub
    override def createService(channel: ManagedChannel): DirectoryProviderServiceStub =
      v0.DirectoryProviderServiceGrpc.stub(channel)
  }

  case class Hello() extends BaseCommand[v0.HelloRequest, v0.HelloResponse, Unit] {

    override def createRequest(): Either[String, v0.HelloRequest] =
      Right(
        v0.HelloRequest()
      )

    override def submitRequest(
        service: DirectoryProviderServiceStub,
        request: v0.HelloRequest,
    ): Future[v0.HelloResponse] = service.hello(request)

    override def handleResponse(
        response: v0.HelloResponse
    ): Either[String, Unit] =
      Right(())
  }
}
