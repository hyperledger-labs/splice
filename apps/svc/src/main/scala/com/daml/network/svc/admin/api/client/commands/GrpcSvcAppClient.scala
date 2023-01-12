package com.daml.network.svc.admin.api.client.commands

import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.svc.v0
import com.daml.network.svc.v0.SvcServiceGrpc.SvcServiceStub
import com.daml.network.svc.v0.{
  GetDebugInfoResponse,
  GrantFeaturedAppRightRequest,
  GrantFeaturedAppRightResponse,
  WithdrawFeaturedAppRightRequest,
}
import com.daml.network.util.Proto
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcSvcAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SvcServiceStub

    override def createService(channel: ManagedChannel): SvcServiceStub =
      v0.SvcServiceGrpc.stub(channel)
  }

  case class DebugInfo(
      svcUser: String,
      svcParty: PartyId,
      coinPackageId: String,
      coinRulesCids: Seq[String],
  )

  case class GetDebugInfo() extends BaseCommand[Empty, GetDebugInfoResponse, DebugInfo] {
    override def createRequest(): Either[String, Empty] = Right(Empty())

    override def submitRequest(
        service: SvcServiceStub,
        request: Empty,
    ): Future[GetDebugInfoResponse] = service.getDebugInfo(request)

    override def handleResponse(
        response: GetDebugInfoResponse
    ): Either[String, DebugInfo] =
      Proto.decode(Proto.Party)(response.svcPartyId).map { svc =>
        DebugInfo(
          svcUser = response.svcUser,
          svcParty = svc,
          coinPackageId = response.coinPackageId,
          coinRulesCids = response.coinRulesContractIds,
        )
      }
  }

  case class GrantFeaturedAppRight(provider: PartyId)
      extends BaseCommand[
        GrantFeaturedAppRightRequest,
        GrantFeaturedAppRightResponse,
        FeaturedAppRight.ContractId,
      ] {

    override def submitRequest(
        service: SvcServiceStub,
        request: GrantFeaturedAppRightRequest,
    ): Future[GrantFeaturedAppRightResponse] = service.grantFeaturedAppRight(request)

    override def createRequest(): Either[String, GrantFeaturedAppRightRequest] = Right(
      GrantFeaturedAppRightRequest(Proto.encode(provider))
    )

    override def handleResponse(
        response: GrantFeaturedAppRightResponse
    ): Either[String, FeaturedAppRight.ContractId] =
      Proto.decodeJavaContractId(FeaturedAppRight.COMPANION)(response.featuredAppRightContractId)
  }

  case class WithdrawFeaturedAppRight(provider: PartyId)
      extends BaseCommand[
        WithdrawFeaturedAppRightRequest,
        Empty,
        Unit,
      ] {

    override def submitRequest(
        service: SvcServiceStub,
        request: WithdrawFeaturedAppRightRequest,
    ): Future[Empty] = service.withdrawFeaturedAppRight(request)

    override def createRequest(): Either[String, WithdrawFeaturedAppRightRequest] = Right(
      WithdrawFeaturedAppRightRequest(Proto.encode(provider))
    )

    /** Handle the response the service has provided
      */
    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
  }

  case class ListConnectedDomains(
  ) extends BaseCommand[Empty, v0.ListConnectedDomainsResponse, Map[DomainAlias, DomainId]] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: SvcServiceStub,
        request: Empty,
    ): Future[v0.ListConnectedDomainsResponse] = service.listConnectedDomains(request)

    override def handleResponse(
        response: v0.ListConnectedDomainsResponse
    ): Either[String, Map[DomainAlias, DomainId]] =
      Proto.decode(Proto.ConnectedDomains)(response.getDomains)
  }
}
