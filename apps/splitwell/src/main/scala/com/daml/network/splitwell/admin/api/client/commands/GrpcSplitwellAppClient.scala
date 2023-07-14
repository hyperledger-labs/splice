package com.daml.network.splitwell.admin.api.client.commands

import cats.implicits.*
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.splitwell.v0
import com.daml.network.splitwell.v0.SplitwellServiceGrpc.SplitwellServiceStub
import com.daml.network.store.MultiDomainAcsStore.{ContractState, ContractWithState}
import com.daml.network.util.{Contract, Codec}
import com.daml.network.v0 as cnv0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.ProtoDeserializationError.FieldNotSet
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
    def toProtoV0: v0.SplitwellContext = v0.SplitwellContext(Codec.encode(partyId))
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
    def toProtoV0: v0.GroupKey = v0.GroupKey(Codec.encode(owner), Codec.encode(provider), id)
    def toPrim: splitwellCodegen.GroupKey = new splitwellCodegen.GroupKey(
      owner.toProtoPrimitive,
      provider.toProtoPrimitive,
      new splitwellCodegen.GroupId(id),
    )
  }

  case class ListGroups(context: SplitwellContext)
      extends BaseCommand[v0.ListGroupsRequest, v0.ListGroupsResponse, Seq[
        ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]
      ]] {
    override def createRequest(): Either[String, v0.ListGroupsRequest] =
      Right(v0.ListGroupsRequest(Some(context.toProtoV0)))

    override def submitRequest(
        service: SplitwellServiceStub,
        request: v0.ListGroupsRequest,
    ): Future[v0.ListGroupsResponse] = service.listGroups(request)

    override def handleResponse(
        response: v0.ListGroupsResponse
    ): Either[String, Seq[
      ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]
    ]] =
      response.groups
        .traverse {
          contractWithStateFromProto(Contract.fromProto(splitwellCodegen.Group.COMPANION))
        }
        .leftMap(_.toString)
  }

  private[GrpcSplitwellAppClient] def contractWithStateFromProto[TCid, T](
      decodeContract: cnv0.Contract => Either[ProtoDeserializationError, Contract[TCid, T]]
  )(encG: cnv0.ContractWithState) = {
    for {
      encContract <- encG.contract.toRight(FieldNotSet("contract"))
      contract <- decodeContract(encContract)
      domainId <- Option
        .when(encG.domainId.nonEmpty)(encG.domainId)
        .traverse(DomainId.fromProtoPrimitive(_, "domain_id"))
    } yield ContractWithState(
      contract,
      domainId.fold(ContractState.InFlight: ContractState)(ContractState.Assigned),
    )
  }

  case class ListGroupInvites(context: SplitwellContext)
      extends BaseCommand[
        v0.ListGroupInvitesRequest,
        v0.ListGroupInvitesResponse,
        Seq[
          ContractWithState[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]
        ],
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
      ContractWithState[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]
    ]] =
      response.groupInvites
        .traverse(
          contractWithStateFromProto(Contract.fromProto(splitwellCodegen.GroupInvite.COMPANION))
        )
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
            k <- Codec.decode(Codec.Party)(k)
            v <- Codec.decode(Codec.BigDecimal)(v)
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
      Codec.decode(Codec.Party)(response.partyId)
  }

  case class SplitwellDomains(
      preferred: DomainId,
      others: Seq[DomainId],
  )
  case class GetSplitwellDomainIds(
  ) extends BaseCommand[Empty, v0.GetSplitwellDomainIdsResponse, SplitwellDomains] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: SplitwellServiceStub,
        request: Empty,
    ): Future[v0.GetSplitwellDomainIdsResponse] = service.getSplitwellDomainIds(request)

    override def handleResponse(
        response: v0.GetSplitwellDomainIdsResponse
    ): Either[String, SplitwellDomains] =
      for {
        preferred <- Codec.decode(Codec.DomainId)(response.preferredDomainId)
        others <- response.otherDomainIds.traverse(id => Codec.decode(Codec.DomainId)(id))
      } yield SplitwellDomains(preferred, others)
  }

  case class GetConnectedDomains(
      partyId: PartyId
  ) extends BaseCommand[v0.GetConnectedDomainsRequest, v0.GetConnectedDomainsResponse, Seq[
        DomainId
      ]] {
    override def createRequest(): Either[String, v0.GetConnectedDomainsRequest] =
      Right(v0.GetConnectedDomainsRequest(Codec.encode(partyId)))

    override def submitRequest(
        service: SplitwellServiceStub,
        request: v0.GetConnectedDomainsRequest,
    ): Future[v0.GetConnectedDomainsResponse] = service.getConnectedDomains(request)

    override def handleResponse(
        response: v0.GetConnectedDomainsResponse
    ): Either[String, Seq[DomainId]] =
      response.domainIds.traverse(id => Codec.decode(Codec.DomainId)(id))
  }

  case class ListSplitwellInstalls(context: SplitwellContext)
      extends BaseCommand[
        v0.ListSplitwellInstallsRequest,
        v0.ListSplitwellInstallsResponse,
        Map[DomainId, splitwellCodegen.SplitwellInstall.ContractId],
      ] {
    override def createRequest(): Either[String, v0.ListSplitwellInstallsRequest] =
      Right(v0.ListSplitwellInstallsRequest(Some(context.toProtoV0)))
    override def submitRequest(
        service: SplitwellServiceStub,
        request: v0.ListSplitwellInstallsRequest,
    ): Future[v0.ListSplitwellInstallsResponse] = service.listSplitwellInstalls(request)

    override def handleResponse(
        response: v0.ListSplitwellInstallsResponse
    ): Either[String, Map[DomainId, splitwellCodegen.SplitwellInstall.ContractId]] =
      for {
        installs <- response.installs.traverse(install =>
          for {
            domain <- Codec.decode(Codec.DomainId)(install.domainId)
            cid <- Codec.decodeJavaContractId(splitwellCodegen.SplitwellInstall.COMPANION)(
              install.contractId
            )
          } yield domain -> cid
        )
      } yield installs.toMap
  }
}
