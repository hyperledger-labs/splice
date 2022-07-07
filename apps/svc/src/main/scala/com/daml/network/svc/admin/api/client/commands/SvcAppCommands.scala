package com.daml.network.svc.admin.api.client.commands

import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.daml.network.examples.v0
import com.daml.network.examples.v0.SvcAppServiceGrpc.SvcAppServiceStub
import io.grpc.ManagedChannel

import scala.concurrent.Future

object SvcAppCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SvcAppServiceStub
    override def createService(channel: ManagedChannel): SvcAppServiceStub =
      v0.SvcAppServiceGrpc.stub(channel)
  }

  case class DummySvcCommmand(some_string: String, some_number: Int)
      extends BaseCommand[v0.SomeDummySvcRequest, v0.SomeDummySvcResponse, Int] {

    override def createRequest(): Either[String, v0.SomeDummySvcRequest] =
      Right(
        v0.SomeDummySvcRequest(
          someString = Some(some_string),
          someNumber = some_number,
        )
      )

    override def submitRequest(
        service: SvcAppServiceStub,
        request: v0.SomeDummySvcRequest,
    ): Future[v0.SomeDummySvcResponse] = service.dummySvcFunction(request)

    override def handleResponse(
        response: v0.SomeDummySvcResponse
    ): Either[String, Int] =
      Right(response.someNumber)
  }

}
