package com.daml.network.validator.admin.api.client.commands

import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.daml.network.examples.v0
import com.daml.network.examples.v0.DummyServiceGrpc.DummyServiceStub
import io.grpc.ManagedChannel

import scala.concurrent.Future

object DummyCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = DummyServiceStub
    override def createService(channel: ManagedChannel): DummyServiceStub =
      v0.DummyServiceGrpc.stub(channel)
  }

  case class DummyCommmand(some_string: String, some_number: Int)
      extends BaseCommand[v0.SomeDummyRequest, v0.SomeDummyResponse, Int] {

    override def createRequest(): Either[String, v0.SomeDummyRequest] =
      Right(
        v0.SomeDummyRequest(
          someString = Some(some_string),
          someNumber = some_number,
        )
      )

    override def submitRequest(
        service: DummyServiceStub,
        request: v0.SomeDummyRequest,
    ): Future[v0.SomeDummyResponse] = service.dummyFunction(request)

    override def handleResponse(
        response: v0.SomeDummyResponse
    ): Either[String, Int] =
      Right(response.someNumber)
  }

}
