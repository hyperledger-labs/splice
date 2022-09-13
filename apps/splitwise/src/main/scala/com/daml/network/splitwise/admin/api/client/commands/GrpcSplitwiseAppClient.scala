package com.daml.network.splitwise.admin.api.client.commands

import cats.implicits._
import com.daml.network.splitwise.v0
import com.daml.network.splitwise.v0.SplitwiseServiceGrpc.SplitwiseServiceStub
import com.daml.network.util.{Contract, Proto}
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.CN.{Splitwise => splitCodegen}
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcSplitwiseAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SplitwiseServiceStub
    override def createService(channel: ManagedChannel): SplitwiseServiceStub =
      v0.SplitwiseServiceGrpc.stub(channel)
  }

  case class GroupKey(
      owner: PartyId,
      provider: PartyId,
      id: String,
  ) {
    def toProtoV0: v0.GroupKey = v0.GroupKey(Proto.encode(owner), Proto.encode(provider), id)
    def toPrim: splitCodegen.GroupKey = splitCodegen.GroupKey(
      owner.toPrim,
      provider.toPrim,
      splitCodegen.GroupId(id),
    )
  }

  case class ListGroups(
  ) extends BaseCommand[Empty, v0.ListGroupsResponse, Seq[Contract[splitCodegen.Group]]] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: Empty,
    ): Future[v0.ListGroupsResponse] = service.listGroups(request)

    override def handleResponse(
        response: v0.ListGroupsResponse
    ): Either[String, Seq[Contract[splitCodegen.Group]]] =
      response.groups.traverse(Contract.fromProto(splitCodegen.Group)(_)).leftMap(_.toString)
  }

  case class ListGroupInvites(
  ) extends BaseCommand[
        Empty,
        v0.ListGroupInvitesResponse,
        Seq[Contract[splitCodegen.GroupInvite]],
      ] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: Empty,
    ): Future[v0.ListGroupInvitesResponse] = service.listGroupInvites(request)

    override def handleResponse(
        response: v0.ListGroupInvitesResponse
    ): Either[String, Seq[Contract[splitCodegen.GroupInvite]]] =
      response.groupInvites
        .traverse(Contract.fromProto(splitCodegen.GroupInvite)(_))
        .leftMap(_.toString)
  }

  case class ListAcceptedGroupInvites(
      provider: PartyId,
      id: String,
  ) extends BaseCommand[
        v0.ListAcceptedGroupInvitesRequest,
        v0.ListAcceptedGroupInvitesResponse,
        Seq[Contract[splitCodegen.AcceptedGroupInvite]],
      ] {
    override def createRequest(): Either[String, v0.ListAcceptedGroupInvitesRequest] =
      Right(v0.ListAcceptedGroupInvitesRequest(Proto.encode(provider), id))

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.ListAcceptedGroupInvitesRequest,
    ): Future[v0.ListAcceptedGroupInvitesResponse] = service.listAcceptedGroupInvites(request)

    override def handleResponse(
        response: v0.ListAcceptedGroupInvitesResponse
    ): Either[String, Seq[Contract[splitCodegen.AcceptedGroupInvite]]] =
      response.acceptedGroupInvites
        .traverse(Contract.fromProto(splitCodegen.AcceptedGroupInvite)(_))
        .leftMap(_.toString)
  }

  case class ListBalanceUpdates(
      key: GroupKey
  ) extends BaseCommand[v0.ListBalanceUpdatesRequest, v0.ListBalanceUpdatesResponse, Seq[
        Contract[splitCodegen.BalanceUpdate]
      ]] {
    override def createRequest(): Either[String, v0.ListBalanceUpdatesRequest] =
      Right(v0.ListBalanceUpdatesRequest(Some(key.toProtoV0)))

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.ListBalanceUpdatesRequest,
    ): Future[v0.ListBalanceUpdatesResponse] = service.listBalanceUpdates(request)

    override def handleResponse(
        response: v0.ListBalanceUpdatesResponse
    ): Either[String, Seq[Contract[splitCodegen.BalanceUpdate]]] =
      response.balanceUpdates
        .traverse(Contract.fromProto(splitCodegen.BalanceUpdate)(_))
        .leftMap(_.toString)
  }

  case class ListBalances(
      key: GroupKey
  ) extends BaseCommand[v0.ListBalancesRequest, v0.ListBalancesResponse, Map[PartyId, BigDecimal]] {
    override def createRequest(): Either[String, v0.ListBalancesRequest] =
      Right(v0.ListBalancesRequest(Some(key.toProtoV0)))

    override def submitRequest(
        service: SplitwiseServiceStub,
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

  case class GetPartyId(
  ) extends BaseCommand[Empty, v0.GetPartyIdResponse, PartyId] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: Empty,
    ): Future[v0.GetPartyIdResponse] = service.getPartyId(request)

    override def handleResponse(
        response: v0.GetPartyIdResponse
    ): Either[String, PartyId] =
      Proto.decode(Proto.Party)(response.partyId)
  }
}
