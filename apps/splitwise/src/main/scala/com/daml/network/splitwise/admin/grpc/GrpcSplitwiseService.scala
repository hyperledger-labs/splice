package com.daml.network.splitwise.admin.grpc

import com.daml.ledger.client.binding.{Contract => CodegenContract, Primitive, Template}
import com.daml.network.codegen.CN.{Splitwise => splitwiseCodegen}
import com.daml.network.codegen.DA
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.v0
import com.daml.network.splitwise.v0.SplitwiseServiceGrpc
import com.daml.network.util.{Contract, Proto}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

@nowarn("cat=unused")
class GrpcSplitwiseService(
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    providerUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SplitwiseServiceGrpc.SplitwiseService
    with Spanning
    with NamedLogging {

  import GrpcSplitwiseService._

  private val connection = ledgerClient.connection("GrpcSplitwiseService")

  def getProviderParty = connection.getPrimaryParty(providerUser)

  override def listGroups(
      request: v0.ListGroupsRequest
  ): Future[v0.ListGroupsResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        providerParty <- getProviderParty
        userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
        // TODO(M1-42): check (or simulate check) of the user's cross-participant access token
        groups <- connection.activeContracts(providerParty, splitwiseCodegen.Group)
      } yield {
        val filtered = groups.filter(c => c.hasStakeholder(userParty.toPrim))
        v0.ListGroupsResponse(filtered.map(c => Contract.fromCodegenContract(c).toProtoV0))
      }
    }

  override def listGroupInvites(
      request: v0.ListGroupInvitesRequest
  ): Future[v0.ListGroupInvitesResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        providerParty <- getProviderParty
        userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
        groupInvites <- connection.activeContracts(providerParty, splitwiseCodegen.GroupInvite)
      } yield {
        val filtered = groupInvites.filter(c => c.hasStakeholder(userParty.toPrim))
        v0.ListGroupInvitesResponse(
          filtered.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  override def listAcceptedGroupInvites(
      request: v0.ListAcceptedGroupInvitesRequest
  ): Future[v0.ListAcceptedGroupInvitesResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        providerParty <- getProviderParty
        userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
        acceptedGroupInvites <- connection.activeContracts(
          providerParty,
          splitwiseCodegen.AcceptedGroupInvite,
        )
      } yield {
        val filtered =
          acceptedGroupInvites.filter(c =>
            c.hasStakeholder(userParty.toPrim) &&
              c.value.groupKey == groupKey_(userParty, providerParty, request.groupId)
          )
        v0.ListAcceptedGroupInvitesResponse(
          filtered.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  override def listBalanceUpdates(
      request: v0.ListBalanceUpdatesRequest
  ): Future[v0.ListBalanceUpdatesResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        providerParty <- getProviderParty
        userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
        balanceUpdates <- connection.activeContracts(providerParty, splitwiseCodegen.BalanceUpdate)
      } yield {
        val filtered = balanceUpdates.filter(c =>
          c.hasStakeholder(userParty.toPrim) &&
            splitwiseCodegen.GroupKey(
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

  override def listBalances(
      request: v0.ListBalancesRequest
  ): Future[v0.ListBalancesResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        providerParty <- getProviderParty
        userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
        balanceUpdates <- connection.activeContracts(providerParty, splitwiseCodegen.BalanceUpdate)
      } yield {
        val filtered = balanceUpdates
          .filter(c =>
            c.hasStakeholder(userParty.toPrim) &&
              splitwiseCodegen
                .GroupKey(
                  c.value.group.owner,
                  c.value.group.provider,
                  c.value.group.id,
                ) == groupKey_(
                request.getGroupKey
              )
          )
          .map(_.value)
        def combine(
            acc: Map[Primitive.Party, BigDecimal],
            update: splitwiseCodegen.BalanceUpdate,
        ): Map[Primitive.Party, BigDecimal] =
          update.update match {
            case splitwiseCodegen.BalanceUpdateType.ExternalPayment(
                  payer,
                  description,
                  quantity,
                ) => {
              val split: BigDecimal = quantity / (update.group.members.length + 1)
              if (payer == userParty.toPrim) {
                (update.group.owner +: update.group.members).foldLeft(acc) { case (acc, member) =>
                  if (member == payer) acc
                  else
                    acc.updatedWith(member)(prev => Some(prev.getOrElse[BigDecimal](0.0) + split))
                }
              } else if ((update.group.owner +: update.group.members).contains(userParty.toPrim)) {
                acc.updatedWith(payer)(prev => Some(prev.getOrElse[BigDecimal](0.0) - split))
              } else {
                acc
              }
            }
            case splitwiseCodegen.BalanceUpdateType.Transfer(sender, receiver, quantity) =>
              if (sender == userParty.toPrim) {
                acc.updatedWith(receiver)(prev => Some(prev.getOrElse[BigDecimal](0.0) + quantity))
              } else if (receiver == userParty.toPrim) {
                acc.updatedWith(sender)(prev => Some(prev.getOrElse[BigDecimal](0.0) - quantity))
              } else acc
            case splitwiseCodegen.BalanceUpdateType.Netting(balanceUpdates) =>
              balanceUpdates
                .getOrElse(userParty.toPrim, Map.empty[Primitive.Party, BigDecimal])
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

  override def getProviderPartyId(request: Empty): Future[v0.GetProviderPartyIdResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { implicit traceContext => span =>
      for {
        party <- getProviderParty
      } yield {
        v0.GetProviderPartyIdResponse(Proto.encode(party))
      }
    }

  private def installKey(
      provider: PartyId,
      user: PartyId,
  ): Template.Key[splitwiseCodegen.SplitwiseInstall] =
    splitwiseCodegen.SplitwiseInstall.key(DA.Types.Tuple2(user.toPrim, provider.toPrim))

  private def groupKey(key: v0.GroupKey): Template.Key[splitwiseCodegen.Group] =
    splitwiseCodegen.Group.key(groupKey_(key))
  private def groupKey_(key: v0.GroupKey): splitwiseCodegen.GroupKey =
    splitwiseCodegen.GroupKey(
      Proto.tryDecode(Proto.CodegenParty)(key.ownerPartyId),
      Proto.tryDecode(Proto.CodegenParty)(key.providerPartyId),
      splitwiseCodegen.GroupId(key.id),
    )
  private def groupKey(
      owner: PartyId,
      provider: PartyId,
      id: String,
  ): Template.Key[splitwiseCodegen.Group] =
    splitwiseCodegen.Group.key(groupKey_(owner, provider, id))
  private def groupKey_(owner: PartyId, provider: PartyId, id: String): splitwiseCodegen.GroupKey =
    splitwiseCodegen.GroupKey(
      owner.toPrim,
      provider.toPrim,
      splitwiseCodegen.GroupId(id),
    )
}

object GrpcSplitwiseService {
  implicit class ContractSyntax[T](private val contract: CodegenContract[T]) extends AnyVal {
    def hasStakeholder(party: Primitive.Party): Boolean =
      contract.signatories.contains(party) || contract.observers.contains(party)
  }
}
