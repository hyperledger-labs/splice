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
import io.opentelemetry.api.trace.Tracer

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
