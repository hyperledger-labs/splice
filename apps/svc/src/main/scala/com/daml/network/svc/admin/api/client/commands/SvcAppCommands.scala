package com.daml.network.svc.admin.api.client.commands

import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.daml.network.examples.v0
import com.daml.network.examples.v0.SvcAppServiceGrpc.SvcAppServiceStub
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object SvcAppCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SvcAppServiceStub
    override def createService(channel: ManagedChannel): SvcAppServiceStub =
      v0.SvcAppServiceGrpc.stub(channel)
  }

  /** A command that takes no input and returns no result (other than an error on failure) */
  // TODO(Arne): Move this somewhere to Canton codebase?
  abstract class UnitCommand extends GrpcAdminCommand[Empty, Empty, Unit] {
    override type Svc = SvcAppServiceStub
    override def createService(channel: ManagedChannel): SvcAppServiceStub =
      v0.SvcAppServiceGrpc.stub(channel)
    override def createRequest(): Either[String, Empty] = Right(Empty())
    override def submitRequest(
        service: SvcAppServiceStub,
        request: Empty,
    ): Future[Empty] = service.initialize(request)
    override def handleResponse(
        response: Empty
    ): Either[String, Unit] = Right(())
  }

  case class Initialize() extends UnitCommand

  case class OpenNextRound() extends UnitCommand

  case class AcceptValidators() extends UnitCommand

}
