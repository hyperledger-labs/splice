package com.daml.network.validator.admin.api.client.commands

import com.daml.network.util.Proto
import com.daml.network.validator.v0
import com.daml.network.validator.v0.ValidatorAppServiceGrpc.ValidatorAppServiceStub
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcValidatorAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = ValidatorAppServiceStub
    override def createService(channel: ManagedChannel): ValidatorAppServiceStub =
      v0.ValidatorAppServiceGrpc.stub(channel)
  }

  case class SetupValidatorCommand() extends BaseCommand[Empty, v0.InitializeResponse, PartyId] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: ValidatorAppServiceStub,
        request: Empty,
    ): Future[v0.InitializeResponse] = service.initialize(request)

    override def handleResponse(
        response: v0.InitializeResponse
    ): Either[String, PartyId] =
      Proto.decode(Proto.Party)(response.partyId)
  }

  case class OnboardUserCommand(name: String)
      extends BaseCommand[v0.OnboardUserRequest, v0.OnboardUserResponse, PartyId] {

    override def createRequest(): Either[String, v0.OnboardUserRequest] =
      Right(v0.OnboardUserRequest(name))

    override def submitRequest(
        service: ValidatorAppServiceStub,
        request: v0.OnboardUserRequest,
    ): Future[v0.OnboardUserResponse] = service.onboardUser(request)

    override def handleResponse(
        response: v0.OnboardUserResponse
    ): Either[String, PartyId] = Proto.decode(Proto.Party)(response.partyId)
  }

  case class InstallWalletForValidator() extends BaseCommand[Empty, Empty, Unit] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: ValidatorAppServiceStub,
        request: Empty,
    ): Future[Empty] = service.installWalletForValidator(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] = Right(())
  }

}
