package com.daml.network.svc.admin.api.client.commands

import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.daml.network.svc.v0
import com.daml.network.svc.v0.{GetDebugInfoResponse, GetValidatorConfigResponse}
import com.daml.network.svc.v0.SvcAppServiceGrpc.SvcAppServiceStub
import com.digitalasset.canton.topology.PartyId
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
  abstract class UnitCommand(adminApiCall: SvcAppServiceStub => (Empty => Future[Empty]))
      extends GrpcAdminCommand[Empty, Empty, Unit] {
    override type Svc = SvcAppServiceStub
    override def createService(channel: ManagedChannel): SvcAppServiceStub =
      v0.SvcAppServiceGrpc.stub(channel)
    override def createRequest(): Either[String, Empty] = Right(Empty())
    override def submitRequest(
        service: SvcAppServiceStub,
        request: Empty,
    ): Future[Empty] = adminApiCall(service)(request)
    override def handleResponse(
        response: Empty
    ): Either[String, Unit] = Right(())
  }

  case class Initialize() extends BaseCommand[Empty, v0.InitializeResponse, PartyId] {

    override def createRequest(): Either[String, Empty] = Right(Empty())

    override def submitRequest(
        service: SvcAppServiceStub,
        request: Empty,
    ): Future[v0.InitializeResponse] = service.initialize(request)

    override def handleResponse(response: v0.InitializeResponse): Either[String, PartyId] = Right(
      PartyId.tryFromProtoPrimitive(response.svcPartyId)
    )
  }

  case class OpenNextRound() extends UnitCommand(_.openNextRound)

  case class AcceptValidators() extends UnitCommand(_.acceptValidators)

  case class DebugInfo(
      svcUser: String,
      svcParty: PartyId,
      coinPackageId: String,
      coinRulesCids: Seq[String],
  )

  case class GetDebugInfo() extends BaseCommand[Empty, GetDebugInfoResponse, DebugInfo] {
    override type Svc = SvcAppServiceStub
    override def createService(channel: ManagedChannel): SvcAppServiceStub =
      v0.SvcAppServiceGrpc.stub(channel)
    override def createRequest(): Either[String, Empty] = Right(Empty())
    override def submitRequest(
        service: SvcAppServiceStub,
        request: Empty,
    ): Future[GetDebugInfoResponse] = service.getDebugInfo(request)
    override def handleResponse(
        response: GetDebugInfoResponse
    ): Either[String, DebugInfo] = Right(
      DebugInfo(
        svcUser = response.svcUser,
        svcParty = PartyId.tryFromProtoPrimitive(response.svcParty),
        coinPackageId = response.coinPackageId,
        coinRulesCids = response.coinRulesCids,
      )
    )
  }

  case class ValidatorConfigInfo(
      svcParty: PartyId
  )

  case class GetValidatorConfig()
      extends BaseCommand[Empty, GetValidatorConfigResponse, ValidatorConfigInfo] {
    override type Svc = SvcAppServiceStub
    override def createService(channel: ManagedChannel): SvcAppServiceStub =
      v0.SvcAppServiceGrpc.stub(channel)
    override def createRequest(): Either[String, Empty] = Right(Empty())
    override def submitRequest(
        service: SvcAppServiceStub,
        request: Empty,
    ): Future[GetValidatorConfigResponse] = service.getValidatorConfig(request)
    override def handleResponse(
        response: GetValidatorConfigResponse
    ): Either[String, ValidatorConfigInfo] = Right(
      ValidatorConfigInfo(
        svcParty = PartyId.tryFromProtoPrimitive(response.svcParty)
      )
    )
  }
}
