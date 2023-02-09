package com.daml.network.splitwell.admin.api.client.commands

import cats.implicits.*
import com.daml.network.codegen.java.cn.{splitwell as splitwellCodegen}
import com.daml.network.splitwell.v0
import com.daml.network.splitwell.v0.SplitwellServiceGrpc.SplitwellServiceStub
import com.daml.network.util.{Contract, Proto}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcSplitwellAppClient {

  // Context passed to all read requests
  case class SplitwellContext(
      partyId: PartyId
  ) {
    def toProtoV0: v0.SplitwellContext = v0.SplitwellContext(Proto.encode(partyId))
  }

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SplitwellServiceStub
    override def createService(channel: ManagedChannel): SplitwellServiceStub =
      v0.SplitwellServiceGrpc.stub(channel)
  }

  case class GroupKey(
      owner: PartyId,
      provider: PartyId,
      id: String,
  ) {
    def toProtoV0: v0.GroupKey = v0.GroupKey(Proto.encode(owner), Proto.encode(provider), id)
    def toPrim: splitwellCodegen.GroupKey = new splitwellCodegen.GroupKey(
      owner.toProtoPrimitive,
      provider.toProtoPrimitive,
      new splitwellCodegen.GroupId(id),
    )
  }

  case class ListGroups(context: SplitwellContext)
      extends BaseCommand[v0.ListGroupsRequest, v0.ListGroupsResponse, Seq[
        Contract[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]
      ]] {
    override def createRequest(): Either[String, v0.ListGroupsRequest] =
      Right(v0.ListGroupsRequest(Some(context.toProtoV0)))

    override def submitRequest(
        service: SplitwellServiceStub,
        request: v0.ListGroupsRequest,
    ): Future[v0.ListGroupsResponse] = service.listGroups(request)

    override def handleResponse(
        response: v0.ListGroupsResponse
    ): Either[String, Seq[Contract[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]]] =
      response.groups
        .traverse(Contract.fromProto(splitwellCodegen.Group.COMPANION)(_))
        .leftMap(_.toString)
  }

  case class ListGroupInvites(context: SplitwellContext)
      extends BaseCommand[
        v0.ListGroupInvitesRequest,
        v0.ListGroupInvitesResponse,
        Seq[Contract[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]],
      ] {
    override def createRequest(): Either[String, v0.ListGroupInvitesRequest] =
      Right(v0.ListGroupInvitesRequest(Some(context.toProtoV0)))

    override def submitRequest(
        service: SplitwellServiceStub,
        request: v0.ListGroupInvitesRequest,
    ): Future[v0.ListGroupInvitesResponse] = service.listGroupInvites(request)

    override def handleResponse(
        response: v0.ListGroupInvitesResponse
    ): Either[String, Seq[
      Contract[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]
    ]] =
      response.groupInvites
        .traverse(Contract.fromProto(splitwellCodegen.GroupInvite.COMPANION)(_))
        .leftMap(_.toString)
  }

  case class ListAcceptedGroupInvites(
      id: String,
      context: SplitwellContext,
  ) extends BaseCommand[
        v0.ListAcceptedGroupInvitesRequest,
        v0.ListAcceptedGroupInvitesResponse,
        Seq[Contract[
          splitwellCodegen.AcceptedGroupInvite.ContractId,
          splitwellCodegen.AcceptedGroupInvite,
        ]],
      ] {
    override def createRequest(): Either[String, v0.ListAcceptedGroupInvitesRequest] =
      Right(v0.ListAcceptedGroupInvitesRequest(id, Some(context.toProtoV0)))

    override def submitRequest(
        service: SplitwellServiceStub,
        request: v0.ListAcceptedGroupInvitesRequest,
    ): Future[v0.ListAcceptedGroupInvitesResponse] = service.listAcceptedGroupInvites(request)

    override def handleResponse(
        response: v0.ListAcceptedGroupInvitesResponse
    ): Either[String, Seq[Contract[
      splitwellCodegen.AcceptedGroupInvite.ContractId,
      splitwellCodegen.AcceptedGroupInvite,
    ]]] =
      response.acceptedGroupInvites
        .traverse(Contract.fromProto(splitwellCodegen.AcceptedGroupInvite.COMPANION)(_))
        .leftMap(_.toString)
  }

  case class ListBalanceUpdates(
      key: GroupKey,
      context: SplitwellContext,
  ) extends BaseCommand[v0.ListBalanceUpdatesRequest, v0.ListBalanceUpdatesResponse, Seq[
        Contract[splitwellCodegen.BalanceUpdate.ContractId, splitwellCodegen.BalanceUpdate]
      ]] {
    override def createRequest(): Either[String, v0.ListBalanceUpdatesRequest] =
      Right(v0.ListBalanceUpdatesRequest(Some(key.toProtoV0), Some(context.toProtoV0)))

    override def submitRequest(
        service: SplitwellServiceStub,
        request: v0.ListBalanceUpdatesRequest,
    ): Future[v0.ListBalanceUpdatesResponse] = service.listBalanceUpdates(request)

    override def handleResponse(
        response: v0.ListBalanceUpdatesResponse
    ): Either[String, Seq[
      Contract[splitwellCodegen.BalanceUpdate.ContractId, splitwellCodegen.BalanceUpdate]
    ]] =
      response.balanceUpdates
        .traverse(Contract.fromProto(splitwellCodegen.BalanceUpdate.COMPANION)(_))
        .leftMap(_.toString)
  }

  case class ListBalances(
      key: GroupKey,
      context: SplitwellContext,
  ) extends BaseCommand[v0.ListBalancesRequest, v0.ListBalancesResponse, Map[PartyId, BigDecimal]] {
    override def createRequest(): Either[String, v0.ListBalancesRequest] =
      Right(v0.ListBalancesRequest(Some(key.toProtoV0), Some(context.toProtoV0)))

    override def submitRequest(
        service: SplitwellServiceStub,
        request: v0.ListBalancesRequest,
    ): Future[v0.ListBalancesResponse] = service.listBalances(request)

    override def handleResponse(
        response: v0.ListBalancesResponse
    ): Either[String, Map[PartyId, BigDecimal]] =
      response.balances.toList
        .traverse { case (k, v) =>
          for {
            k <- Proto.decode(Proto.Party)(k)
            v <- Proto.decode(Proto.BigDecimal)(v)
          } yield k -> v
        }
        .map(_.toMap)
        .leftMap(_.toString)
  }

  case class GetProviderPartyId(
  ) extends BaseCommand[Empty, v0.GetProviderPartyIdResponse, PartyId] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: SplitwellServiceStub,
        request: Empty,
    ): Future[v0.GetProviderPartyIdResponse] = service.getProviderPartyId(request)

    override def handleResponse(
        response: v0.GetProviderPartyIdResponse
    ): Either[String, PartyId] =
      Proto.decode(Proto.Party)(response.partyId)
  }

  case class ListConnectedDomains(
  ) extends BaseCommand[Empty, v0.ListConnectedDomainsResponse, Map[DomainAlias, DomainId]] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: SplitwellServiceStub,
        request: Empty,
    ): Future[v0.ListConnectedDomainsResponse] = service.listConnectedDomains(request)

    override def handleResponse(
        response: v0.ListConnectedDomainsResponse
    ): Either[String, Map[DomainAlias, DomainId]] =
      Proto.decode(Proto.ConnectedDomains)(response.getDomains)
  }

  case class GetSplitwellDomainId(
  ) extends BaseCommand[Empty, v0.GetSplitwellDomainIdResponse, DomainId] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: SplitwellServiceStub,
        request: Empty,
    ): Future[v0.GetSplitwellDomainIdResponse] = service.getSplitwellDomainId(request)

    override def handleResponse(
        response: v0.GetSplitwellDomainIdResponse
    ): Either[String, DomainId] =
      Proto.decode(Proto.DomainId)(response.domainId)
  }
}
