package com.daml.network.validator.admin.api.client.commands

import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.daml.network.examples.v0
import com.daml.network.examples.v0.ValidatorAppServiceGrpc.ValidatorAppServiceStub
import io.grpc.ManagedChannel

import scala.concurrent.Future

object ValidatorAppCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = ValidatorAppServiceStub
    override def createService(channel: ManagedChannel): ValidatorAppServiceStub =
      v0.ValidatorAppServiceGrpc.stub(channel)
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
        service: ValidatorAppServiceStub,
        request: v0.SomeDummyRequest,
    ): Future[v0.SomeDummyResponse] = service.dummyFunction(request)

    override def handleResponse(
        response: v0.SomeDummyResponse
    ): Either[String, Int] =
      Right(response.someNumber)
  }

  case class SetupValidatorCommand(name : String, svc: PartyId)
      extends BaseCommand[v0.SetupValidatorRequest, v0.SetupValidatorResponse, PartyId] {

    override def createRequest() : Either[String, v0.SetupValidatorRequest] =
        Right(v0.SetupValidatorRequest(Some(name), Some(svc.toPrim.toString)))

    override def submitRequest(
        service : ValidatorAppServiceStub,
        request : v0.SetupValidatorRequest,
        ): Future[v0.SetupValidatorResponse] = service.setupValidator(request)

    override def handleResponse(
        response : v0.SetupValidatorResponse
        ): Either[String, PartyId] =
           response.partyId
             .toRight("Missing mandatory field: party_id")
             .map(PartyId.tryFromProtoPrimitive) 
  }

  case class OnboardUserCommand(name : String)
      extends BaseCommand[v0.OnboardUserRequest, v0.OnboardUserResponse, PartyId] {

    override def createRequest(): Either[String, v0.OnboardUserRequest] =
      Right(
        v0.OnboardUserRequest(Some(name))
      )

    override def submitRequest(
        service: ValidatorAppServiceStub,
        request: v0.OnboardUserRequest,
    ): Future[v0.OnboardUserResponse] = service.onboardUser(request)

    override def handleResponse(
        response: v0.OnboardUserResponse
    ): Either[String, PartyId] =
      response.partyId
        .toRight("Missing mandatory field: party_id")
        .map(PartyId.tryFromProtoPrimitive) 
  }

}
