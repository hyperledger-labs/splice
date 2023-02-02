package com.daml.network.sv.admin.api.client.commands

import com.daml.network.sv.v0
import com.daml.network.sv.v0.GetDebugInfoResponse
import com.daml.network.sv.v0.SvServiceGrpc.SvServiceStub
import com.daml.network.util.Proto
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.{DomainId, PartyId}
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
      svcParty: PartyId,
      coinPackageId: String,
      svcGovernancePackageId: String,
      coinRulesCid: String,
      svcRulesCid: String,
      ongoingValidatorOnboardings: Int,
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
      for {
        svParty <- Proto.decode(Proto.Party)(response.svPartyId)
        svcParty <- Proto.decode(Proto.Party)(response.svcPartyId)
      } yield DebugInfo(
        svUser = response.svUser,
        svParty = svParty,
        svcParty = svcParty,
        coinPackageId = response.coinPackageId,
        svcGovernancePackageId = response.svcGovernancePackageId,
        coinRulesCid = response.coinRulesContractId,
        svcRulesCid = response.coinRulesContractId,
        ongoingValidatorOnboardings = response.ongoingValidatorOnboardings,
      )
  }

  case class ListConnectedDomains(
  ) extends BaseCommand[Empty, v0.ListConnectedDomainsResponse, Map[DomainAlias, DomainId]] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: SvServiceStub,
        request: Empty,
    ): Future[v0.ListConnectedDomainsResponse] = service.listConnectedDomains(request)

    override def handleResponse(
        response: v0.ListConnectedDomainsResponse
    ): Either[String, Map[DomainAlias, DomainId]] =
      Proto.decode(Proto.ConnectedDomains)(response.getDomains)
  }
}
