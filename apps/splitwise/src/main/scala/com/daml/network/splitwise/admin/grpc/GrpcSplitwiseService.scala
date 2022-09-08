package com.daml.network.splitwise.admin.grpc

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.{Primitive, Template}
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.v0
import com.daml.network.splitwise.v0.SplitwiseServiceGrpc
import com.daml.network.util.{Contract, Proto}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.daml.network.codegen.DA
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.codegen.CN.{Splitwise => splitCodegen, Wallet => walletCodegen}
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

@nowarn("cat=unused")
class GrpcSplitwiseService(
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    damlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SplitwiseServiceGrpc.SplitwiseService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcSplitwiseService")

  private val collectionDuration = RelTime(
    10_000_000
  )
  private val acceptDuration = RelTime(
    60_000_000
  )

  val validatorParty: AtomicReference[Option[PartyId]] = new AtomicReference[Option[PartyId]](None)

  def getValidatorParty: PartyId =
    validatorParty.get.getOrElse(
      throw new StatusRuntimeException(
        Status.FAILED_PRECONDITION.withDescription(
          "Splitwise is not initialized, run splitwise.initialize(validatorParty) first"
        )
      )
    )

  override def initialize(request: v0.InitializeRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => _ =>
      Future {
        validatorParty.set(Some(PartyId.tryFromProtoPrimitive(request.validatorPartyId)))
        Empty()
      }
    }

  def createInstallProposal(
      request: v0.CreateInstallProposalRequest
  ): Future[v0.CreateInstallProposalResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        cid <- connection.submitWithResult(
          Seq(party),
          Seq.empty,
          splitCodegen
            .SplitwiseInstallProposal(
              user = party.toPrim,
              provider = Proto.tryDecode(Proto.CodegenParty)(request.provider),
            )
            .create,
        )
      } yield v0.CreateInstallProposalResponse(Proto.encode(cid))
    }

  def acceptInstallProposal(
      request: v0.AcceptInstallProposalRequest
  ): Future[v0.AcceptInstallProposalResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        cid <- connection.submitWithResult(
          Seq(party),
          Seq.empty,
          Proto
            .tryDecodeContractId[splitCodegen.SplitwiseInstallProposal](request.proposalContractId)
            .exerciseSplitwiseInstallProposal_Accept(),
        )
      } yield v0.AcceptInstallProposalResponse(Proto.encode(cid))
    }

  def createGroup(
      request: v0.CreateGroupRequest
  ): Future[v0.CreateGroupResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        svc <- scanConnection.getSvcPartyId()
        provider = Proto.tryDecode(Proto.Party)(request.providerPartyId)
        cid <- connection.submitWithResult(
          Seq(party),
          Seq.empty,
          installKey(provider, party).exerciseSplitwiseInstall_CreateGroup(
            splitCodegen.Group(
              owner = party.toPrim,
              provider = provider.toPrim,
              svc = svc.toPrim,
              members = Seq.empty,
              id = splitCodegen.GroupId(request.groupId),
              collectionDuration = collectionDuration,
              acceptDuration = acceptDuration,
            )
          ),
        )
      } yield v0.CreateGroupResponse(Proto.encode(cid))
    }
  def createGroupInvite(
      request: v0.CreateGroupInviteRequest
  ): Future[v0.CreateGroupInviteResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        observers = request.observerPartyIds.map(Proto.tryDecode(Proto.CodegenParty))
        provider = Proto.tryDecode(Proto.Party)(request.providerPartyId)
        cid <- connection.submitWithResult(
          Seq(party),
          Seq.empty,
          installKey(provider, party).exerciseSplitwiseInstall_CreateInvite(
            groupKey_(party, provider, request.groupId),
            observers,
          ),
        )
      } yield v0.CreateGroupInviteResponse(Proto.encode(cid))
    }
  def joinGroup(
      request: v0.JoinGroupRequest
  ): Future[v0.JoinGroupResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        provider = Proto.tryDecode(Proto.Party)(request.providerPartyId)
        cid <- connection.submitWithResult(
          Seq(party),
          Seq.empty,
          installKey(provider, party).exerciseSplitwiseInstall_Join(
            Proto
              .tryDecodeContractId[splitCodegen.AcceptedGroupInvite](
                request.acceptedGroupInviteContractId
              )
          ),
        )
      } yield v0.JoinGroupResponse(Proto.encode(cid))
    }

  def acceptInvite(
      request: v0.AcceptInviteRequest
  ): Future[v0.AcceptInviteResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        provider = Proto.tryDecode(Proto.Party)(request.providerPartyId)
        cid <- connection.submitWithResult(
          Seq(party),
          Seq.empty,
          installKey(provider, party).exerciseSplitwiseInstall_AcceptInvite(
            Proto
              .tryDecodeContractId[splitCodegen.GroupInvite](request.groupInviteContractId)
          ),
        )
      } yield v0.AcceptInviteResponse(
        Proto.encode(cid)
      )
    }

  def enterPayment(
      request: v0.EnterPaymentRequest
  ): Future[v0.EnterPaymentResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        provider = Proto.tryDecode(Proto.Party)(request.providerPartyId)
        cid <- connection.submitWithResult(
          Seq(party),
          Seq.empty,
          installKey(provider, party).exerciseSplitwiseInstall_EnterPayment(
            groupKey_(request.getGroupKey),
            Proto.tryDecode(Proto.BigDecimal)(request.quantity),
            request.description,
          ),
        )
      } yield v0.EnterPaymentResponse(
        Proto.encode(cid)
      )
    }
  def initiateTransfer(
      request: v0.InitiateTransferRequest
  ): Future[v0.InitiateTransferResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        provider = Proto.tryDecode(Proto.Party)(request.providerPartyId)
        cid <- connection.submitWithResult(
          Seq(party),
          Seq.empty,
          installKey(provider, party).exerciseSplitwiseInstall_InitiateTransfer(
            groupKey_(request.getGroupKey),
            Proto.tryDecode(Proto.CodegenParty)(request.receiverPartyId),
            Proto.tryDecode(Proto.BigDecimal)(request.quantity),
          ),
        )
      } yield v0.InitiateTransferResponse(
        Proto.encode(cid)
      )
    }
  def completeTransfer(
      request: v0.CompleteTransferRequest
  ): Future[v0.CompleteTransferResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        provider = Proto.tryDecode(Proto.Party)(request.providerPartyId)
        cid <- connection.submitWithResult(
          Seq(party),
          Seq(getValidatorParty),
          installKey(provider, party).exerciseSplitwiseInstall_CompleteTransfer(
            groupKey_(request.getGroupKey),
            Proto.tryDecodeContractId[walletCodegen.AcceptedAppPayment](
              request.acceptedAppPaymentContractId
            ),
          ),
        )
      } yield v0.CompleteTransferResponse(
        Proto.encode(cid)
      )
    }

  def net(
      request: v0.NetRequest
  ): Future[v0.NetResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        provider = Proto.tryDecode(Proto.Party)(request.providerPartyId)
        balanceUpdates = request.balanceUpdates.map { case (k, v) =>
          Proto.tryDecode(Proto.CodegenParty)(k) -> (v.balanceUpdatesPerParty.map { case (k, v) =>
            Proto.tryDecode(Proto.CodegenParty)(k) -> Proto.tryDecode(Proto.BigDecimal)(v)
          }: Primitive.GenMap[Primitive.Party, Primitive.Numeric])
        }
        cid <- connection.submitWithResult(
          Seq(party),
          Seq.empty,
          installKey(provider, party).exerciseSplitwiseInstall_Net(
            groupKey_(request.getGroupKey),
            balanceChanges = balanceUpdates,
          ),
        )
      } yield v0.NetResponse(
        Proto.encode(cid)
      )
    }

  def listGroups(
      request: com.google.protobuf.empty.Empty
  ): Future[v0.ListGroupsResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        groups <- connection.activeContracts(party, splitCodegen.Group)
      } yield v0.ListGroupsResponse(groups.map(c => Contract.fromCodegenContract(c).toProtoV0))
    }

  def listGroupInvites(
      request: com.google.protobuf.empty.Empty
  ): Future[v0.ListGroupInvitesResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        groupInvites <- connection.activeContracts(party, splitCodegen.GroupInvite)
      } yield v0.ListGroupInvitesResponse(
        groupInvites.map(c => Contract.fromCodegenContract(c).toProtoV0)
      )
    }

  def listAcceptedGroupInvites(
      request: v0.ListAcceptedGroupInvitesRequest
  ): Future[v0.ListAcceptedGroupInvitesResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        provider = Proto.tryDecode(Proto.Party)(request.providerPartyId)
        acceptedGroupInvites <- connection.activeContracts(party, splitCodegen.AcceptedGroupInvite)
      } yield {
        val filtered =
          acceptedGroupInvites.filter(c =>
            c.value.groupKey == groupKey_(party, provider, request.groupId)
          )
        v0.ListAcceptedGroupInvitesResponse(
          filtered.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  def listBalanceUpdates(
      request: v0.ListBalanceUpdatesRequest
  ): Future[v0.ListBalanceUpdatesResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        balanceUpdates <- connection.activeContracts(party, splitCodegen.BalanceUpdate)
      } yield {
        val filtered = balanceUpdates.filter(c =>
          splitCodegen.GroupKey(
            c.value.group.owner,
            c.value.group.provider,
            c.value.group.id,
          ) == groupKey_(
            request.getGroupKey
          )
        )
        v0.ListBalanceUpdatesResponse(filtered.map(c => Contract.fromCodegenContract(c).toProtoV0))
      }
    }

  def listBalances(
      request: v0.ListBalancesRequest
  ): Future[v0.ListBalancesResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        balanceUpdates <- connection.activeContracts(party, splitCodegen.BalanceUpdate)
      } yield {
        val filtered = balanceUpdates
          .filter(c =>
            splitCodegen
              .GroupKey(c.value.group.owner, c.value.group.provider, c.value.group.id) == groupKey_(
              request.getGroupKey
            )
          )
          .map(_.value)
        def combine(
            acc: Map[Primitive.Party, BigDecimal],
            update: splitCodegen.BalanceUpdate,
        ): Map[Primitive.Party, BigDecimal] =
          update.update match {
            case splitCodegen.BalanceUpdateType.ExternalPayment(payer, description, quantity) => {
              val split: BigDecimal = quantity / (update.group.members.length + 1)
              if (payer == party.toPrim) {
                (update.group.owner +: update.group.members).foldLeft(acc) { case (acc, member) =>
                  if (member == payer) acc
                  else
                    acc.updatedWith(member)(prev => Some(prev.getOrElse[BigDecimal](0.0) + split))
                }
              } else if ((update.group.owner +: update.group.members).contains(party.toPrim)) {
                acc.updatedWith(payer)(prev => Some(prev.getOrElse[BigDecimal](0.0) - split))
              } else {
                acc
              }
            }
            case splitCodegen.BalanceUpdateType.Transfer(sender, receiver, quantity) =>
              if (sender == party.toPrim) {
                acc.updatedWith(receiver)(prev => Some(prev.getOrElse[BigDecimal](0.0) + quantity))
              } else if (receiver == party.toPrim) {
                acc.updatedWith(sender)(prev => Some(prev.getOrElse[BigDecimal](0.0) - quantity))
              } else acc
            case splitCodegen.BalanceUpdateType.Netting(balanceUpdates) =>
              balanceUpdates
                .getOrElse(party.toPrim, Map.empty[Primitive.Party, BigDecimal])
                .iterator
                .foldLeft(acc) { case (acc, (k, v)) =>
                  acc.updatedWith(k)(prev => Some(prev.getOrElse[BigDecimal](0.0) + v))
                }
          }
        val balances: Map[Primitive.Party, BigDecimal] =
          filtered.foldLeft(Map.empty[Primitive.Party, BigDecimal])(combine)
        v0.ListBalancesResponse(balances.map { case (k, v) => Proto.encode(k) -> Proto.encode(v) })
      }
    }

  def listAcceptedAppPayments(
      request: v0.ListAcceptedAppPaymentsRequest
  ): Future[v0.ListAcceptedAppPaymentsResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
        transfersInProgress <- connection.activeContracts(party, splitCodegen.TransferInProgress)
        acceptedPayments <- connection.activeContracts(party, walletCodegen.AcceptedAppPayment)
      } yield {
        val filteredInProgress: Set[ApiTypes.ContractId] = transfersInProgress
          .filter(c =>
            splitCodegen
              .GroupKey(c.value.group.owner, c.value.group.provider, c.value.group.id) == groupKey_(
              request.getGroupKey
            ) && c.value.sender == party.toPrim
          )
          .map(_.contractId)
          .toSet
        val filteredPayments = acceptedPayments.filter(payment =>
          filteredInProgress.contains(payment.value.reference: ApiTypes.ContractId)
        )
        v0.ListAcceptedAppPaymentsResponse(
          filteredPayments.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  def getPartyId(request: Empty): Future[v0.GetPartyIdResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(damlUser)
      } yield {
        v0.GetPartyIdResponse(Proto.encode(party))
      }
    }

  private def installKey(
      provider: PartyId,
      user: PartyId,
  ): Template.Key[splitCodegen.SplitwiseInstall] =
    splitCodegen.SplitwiseInstall.key(DA.Types.Tuple2(user.toPrim, provider.toPrim))

  private def groupKey(key: v0.GroupKey): Template.Key[splitCodegen.Group] =
    splitCodegen.Group.key(groupKey_(key))
  private def groupKey_(key: v0.GroupKey): splitCodegen.GroupKey =
    splitCodegen.GroupKey(
      Proto.tryDecode(Proto.CodegenParty)(key.ownerPartyId),
      Proto.tryDecode(Proto.CodegenParty)(key.providerPartyId),
      splitCodegen.GroupId(key.id),
    )
  private def groupKey(
      owner: PartyId,
      provider: PartyId,
      id: String,
  ): Template.Key[splitCodegen.Group] =
    splitCodegen.Group.key(groupKey_(owner, provider, id))
  private def groupKey_(owner: PartyId, provider: PartyId, id: String): splitCodegen.GroupKey =
    splitCodegen.GroupKey(
      owner.toPrim,
      provider.toPrim,
      splitCodegen.GroupId(id),
    )
}
