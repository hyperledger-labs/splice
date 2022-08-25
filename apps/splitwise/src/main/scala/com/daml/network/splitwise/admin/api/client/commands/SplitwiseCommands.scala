package com.daml.network.splitwise.admin.api.client.commands

import cats.implicits._
import com.daml.ledger.client.binding.Primitive
import com.daml.network.splitwise.v0
import com.daml.network.splitwise.v0.SplitwiseServiceGrpc.SplitwiseServiceStub
import com.daml.network.util.{Contract, Proto}
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.CN.{Splitwise => splitCodegen, Wallet => walletCodegen}
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object SplitwiseCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SplitwiseServiceStub
    override def createService(channel: ManagedChannel): SplitwiseServiceStub =
      v0.SplitwiseServiceGrpc.stub(channel)
  }

  case class CreateInstallProposal(provider: PartyId)
      extends BaseCommand[
        v0.CreateInstallProposalRequest,
        v0.CreateInstallProposalResponse,
        Primitive.ContractId[
          splitCodegen.SplitwiseInstallProposal
        ],
      ] {
    override def createRequest(): Either[String, v0.CreateInstallProposalRequest] =
      Right(v0.CreateInstallProposalRequest(Proto.encode(provider)))

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.CreateInstallProposalRequest,
    ): Future[v0.CreateInstallProposalResponse] = service.createInstallProposal(request)

    override def handleResponse(
        response: v0.CreateInstallProposalResponse
    ): Either[String, Primitive.ContractId[splitCodegen.SplitwiseInstallProposal]] =
      Proto.decodeContractId[splitCodegen.SplitwiseInstallProposal](response.proposalContractId)
  }

  case class AcceptInstallProposal(
      proposalCid: Primitive.ContractId[splitCodegen.SplitwiseInstallProposal]
  ) extends BaseCommand[
        v0.AcceptInstallProposalRequest,
        v0.AcceptInstallProposalResponse,
        Primitive.ContractId[
          splitCodegen.SplitwiseInstall
        ],
      ] {
    override def createRequest(): Either[String, v0.AcceptInstallProposalRequest] =
      Right(v0.AcceptInstallProposalRequest(Proto.encode(proposalCid)))

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.AcceptInstallProposalRequest,
    ): Future[v0.AcceptInstallProposalResponse] = service.acceptInstallProposal(request)

    override def handleResponse(
        response: v0.AcceptInstallProposalResponse
    ): Either[String, Primitive.ContractId[splitCodegen.SplitwiseInstall]] =
      Proto.decodeContractId[splitCodegen.SplitwiseInstall](response.installContractId)
  }

  case class CreateGroup(provider: PartyId, id: String)
      extends BaseCommand[v0.CreateGroupRequest, v0.CreateGroupResponse, Primitive.ContractId[
        splitCodegen.Group
      ]] {
    override def createRequest(): Either[String, v0.CreateGroupRequest] =
      Right(v0.CreateGroupRequest(Proto.encode(provider), id))

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.CreateGroupRequest,
    ): Future[v0.CreateGroupResponse] = service.createGroup(request)

    override def handleResponse(
        response: v0.CreateGroupResponse
    ): Either[String, Primitive.ContractId[splitCodegen.Group]] =
      Proto.decodeContractId[splitCodegen.Group](response.groupContractId)
  }

  case class CreateGroupInvite(provider: PartyId, id: String, observers: Seq[PartyId])
      extends BaseCommand[
        v0.CreateGroupInviteRequest,
        v0.CreateGroupInviteResponse,
        Primitive.ContractId[
          splitCodegen.GroupInvite
        ],
      ] {
    override def createRequest(): Either[String, v0.CreateGroupInviteRequest] =
      Right(v0.CreateGroupInviteRequest(Proto.encode(provider), id, observers.map(Proto.encode(_))))

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.CreateGroupInviteRequest,
    ): Future[v0.CreateGroupInviteResponse] = service.createGroupInvite(request)

    override def handleResponse(
        response: v0.CreateGroupInviteResponse
    ): Either[String, Primitive.ContractId[splitCodegen.GroupInvite]] =
      Proto.decodeContractId[splitCodegen.GroupInvite](response.groupInviteContractId)
  }

  case class JoinGroup(
      provider: PartyId,
      acceptedGroupInvite: Primitive.ContractId[splitCodegen.AcceptedGroupInvite],
  ) extends BaseCommand[v0.JoinGroupRequest, v0.JoinGroupResponse, Primitive.ContractId[
        splitCodegen.Group
      ]] {
    override def createRequest(): Either[String, v0.JoinGroupRequest] =
      Right(v0.JoinGroupRequest(Proto.encode(provider), Proto.encode(acceptedGroupInvite)))

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.JoinGroupRequest,
    ): Future[v0.JoinGroupResponse] = service.joinGroup(request)

    override def handleResponse(
        response: v0.JoinGroupResponse
    ): Either[String, Primitive.ContractId[splitCodegen.Group]] =
      Proto.decodeContractId[splitCodegen.Group](response.groupContractId)
  }

  case class AcceptInvite(
      provider: PartyId,
      groupInvite: Primitive.ContractId[splitCodegen.GroupInvite],
  ) extends BaseCommand[v0.AcceptInviteRequest, v0.AcceptInviteResponse, Primitive.ContractId[
        splitCodegen.AcceptedGroupInvite
      ]] {
    override def createRequest(): Either[String, v0.AcceptInviteRequest] =
      Right(v0.AcceptInviteRequest(Proto.encode(provider), Proto.encode(groupInvite)))

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.AcceptInviteRequest,
    ): Future[v0.AcceptInviteResponse] = service.acceptInvite(request)

    override def handleResponse(
        response: v0.AcceptInviteResponse
    ): Either[String, Primitive.ContractId[splitCodegen.AcceptedGroupInvite]] =
      Proto.decodeContractId[splitCodegen.AcceptedGroupInvite](
        response.acceptedGroupInviteContractId
      )
  }

  case class GroupKey(
      owner: PartyId,
      provider: PartyId,
      id: String,
  ) {
    def toProtoV0: v0.GroupKey = v0.GroupKey(Proto.encode(owner), Proto.encode(provider), id)
  }

  case class EnterPayment(
      provider: PartyId,
      key: GroupKey,
      quantity: BigDecimal,
      description: String,
  ) extends BaseCommand[v0.EnterPaymentRequest, v0.EnterPaymentResponse, Primitive.ContractId[
        splitCodegen.BalanceUpdate
      ]] {
    override def createRequest(): Either[String, v0.EnterPaymentRequest] =
      Right(
        v0.EnterPaymentRequest(
          Proto.encode(provider),
          Some(key.toProtoV0),
          Proto.encode(quantity),
          description,
        )
      )

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.EnterPaymentRequest,
    ): Future[v0.EnterPaymentResponse] = service.enterPayment(request)

    override def handleResponse(
        response: v0.EnterPaymentResponse
    ): Either[String, Primitive.ContractId[splitCodegen.BalanceUpdate]] =
      Proto.decodeContractId[splitCodegen.BalanceUpdate](response.balanceUpdateContractId)
  }

  case class InitiateTransfer(
      provider: PartyId,
      key: GroupKey,
      receiver: PartyId,
      quantity: BigDecimal,
  ) extends BaseCommand[
        v0.InitiateTransferRequest,
        v0.InitiateTransferResponse,
        Primitive.ContractId[
          walletCodegen.TransferRequest
        ],
      ] {
    override def createRequest(): Either[String, v0.InitiateTransferRequest] =
      Right(
        v0.InitiateTransferRequest(
          Proto.encode(provider),
          Some(key.toProtoV0),
          Proto.encode(receiver),
          Proto.encode(quantity),
        )
      )

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.InitiateTransferRequest,
    ): Future[v0.InitiateTransferResponse] = service.initiateTransfer(request)

    override def handleResponse(
        response: v0.InitiateTransferResponse
    ): Either[String, Primitive.ContractId[walletCodegen.TransferRequest]] =
      Proto.decodeContractId[walletCodegen.TransferRequest](response.transferRequestContractId)
  }

  case class CompleteTransfer(
      provider: PartyId,
      key: GroupKey,
      receipt: Primitive.ContractId[walletCodegen.TransferReceipt],
  ) extends BaseCommand[
        v0.CompleteTransferRequest,
        v0.CompleteTransferResponse,
        Primitive.ContractId[
          splitCodegen.BalanceUpdate
        ],
      ] {
    override def createRequest(): Either[String, v0.CompleteTransferRequest] =
      Right(
        v0.CompleteTransferRequest(
          Proto.encode(provider),
          Some(key.toProtoV0),
          Proto.encode(receipt),
        )
      )

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.CompleteTransferRequest,
    ): Future[v0.CompleteTransferResponse] = service.completeTransfer(request)

    override def handleResponse(
        response: v0.CompleteTransferResponse
    ): Either[String, Primitive.ContractId[splitCodegen.BalanceUpdate]] =
      Proto.decodeContractId[splitCodegen.BalanceUpdate](response.balanceUpdateContractId)
  }

  case class Net(
      provider: PartyId,
      key: GroupKey,
      balanceChanges: Map[PartyId, Map[PartyId, BigDecimal]],
  ) extends BaseCommand[
        v0.NetRequest,
        v0.NetResponse,
        Primitive.ContractId[
          splitCodegen.BalanceUpdate
        ],
      ] {
    override def createRequest(): Either[String, v0.NetRequest] =
      Right(
        v0.NetRequest(
          Proto.encode(provider),
          Some(key.toProtoV0),
          balanceChanges.map { case (k, v) =>
            Proto.encode(k) -> v0.BalanceUpdatePerParty(v.map { case (k, v) =>
              Proto.encode(k) -> Proto.encode(v)
            })
          },
        )
      )

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.NetRequest,
    ): Future[v0.NetResponse] = service.net(request)

    override def handleResponse(
        response: v0.NetResponse
    ): Either[String, Primitive.ContractId[splitCodegen.BalanceUpdate]] =
      Proto.decodeContractId[splitCodegen.BalanceUpdate](response.balanceUpdateContractId)
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

  case class ListTransferReceipts(
      key: GroupKey
  ) extends BaseCommand[v0.ListTransferReceiptsRequest, v0.ListTransferReceiptsResponse, Seq[
        Contract[walletCodegen.TransferReceipt]
      ]] {
    override def createRequest(): Either[String, v0.ListTransferReceiptsRequest] =
      Right(v0.ListTransferReceiptsRequest(Some(key.toProtoV0)))

    override def submitRequest(
        service: SplitwiseServiceStub,
        request: v0.ListTransferReceiptsRequest,
    ): Future[v0.ListTransferReceiptsResponse] = service.listTransferReceipts(request)

    override def handleResponse(
        response: v0.ListTransferReceiptsResponse
    ): Either[String, Seq[Contract[walletCodegen.TransferReceipt]]] =
      response.transferReceipts
        .traverse(Contract.fromProto(walletCodegen.TransferReceipt)(_))
        .leftMap(_.toString)
  }
}
