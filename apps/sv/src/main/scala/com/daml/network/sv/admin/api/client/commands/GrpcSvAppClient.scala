package com.daml.network.sv.admin.api.client.commands

import com.daml.network.sv.v0
import com.daml.network.sv.v0.GetDebugInfoResponse
import com.daml.network.sv.v0.SvServiceGrpc.SvServiceStub
import com.daml.network.util.Proto
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcSvAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SvServiceStub

    override def createService(channel: ManagedChannel): SvServiceStub =
      v0.SvServiceGrpc.stub(channel)
  }

  case class DebugInfo(
      svUser: String,
      svParty: PartyId,
      coinPackageId: String,
      coinRulesCids: Seq[String],
  )

  case class GetDebugInfo() extends BaseCommand[Empty, GetDebugInfoResponse, DebugInfo] {
    override def createRequest(): Either[String, Empty] = Right(Empty())

    override def submitRequest(
        service: SvServiceStub,
        request: Empty,
    ): Future[GetDebugInfoResponse] = service.getDebugInfo(request)

    override def handleResponse(
        response: GetDebugInfoResponse
    ): Either[String, DebugInfo] =
      Proto.decode(Proto.Party)(response.svPartyId).map { sv =>
        DebugInfo(
          svUser = response.svUser,
          svParty = sv,
          coinPackageId = response.coinPackageId,
          coinRulesCids = response.coinRulesContractIds,
        )
      }
  }
}
