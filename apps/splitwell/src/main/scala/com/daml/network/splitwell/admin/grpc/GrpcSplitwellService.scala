package com.daml.network.splitwell.admin.grpc

import com.daml.ledger.javaapi.data.codegen.{Contract as CodegenContract}
import com.daml.network.codegen.java.cn.{splitwell as splitwellCodegen}
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.splitwell.v0
import com.daml.network.splitwell.v0.SplitwellServiceGrpc
import com.daml.network.util.{Contract, Proto}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

@nowarn("cat=unused")
class GrpcSplitwellService(
    ledgerClient: CoinLedgerClient,
    splitwellDomainId: DomainId,
    scanConnection: ScanConnection,
    providerParty: PartyId,
    store: SplitwellStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SplitwellServiceGrpc.SplitwellService
    with Spanning
    with NamedLogging {

  import GrpcSplitwellService.*

  private val connection = ledgerClient.connection()

  override def listGroups(
      request: v0.ListGroupsRequest
  ): Future[v0.ListGroupsResponse] =
    withSpanFromGrpcContext("GrpcSplitwellService") { implicit traceContext => span =>
      val userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
      for {
        // TODO(M4-02): check (or simulate check) of the user's cross-participant access token
        groups <- connection.activeContracts(
          splitwellDomainId,
          providerParty,
          splitwellCodegen.Group.COMPANION,
        )
      } yield {
        val filtered = groups.filter(c => c.hasStakeholder(userParty))
        v0.ListGroupsResponse(filtered.map(c => Contract.fromCodegenContract(c, None).toProtoV0))
      }
    }

  override def listGroupInvites(
      request: v0.ListGroupInvitesRequest
  ): Future[v0.ListGroupInvitesResponse] =
    withSpanFromGrpcContext("GrpcSplitwellService") { implicit traceContext => span =>
      val userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
      for {
        groupInvites <- connection.activeContracts(
          splitwellDomainId,
          providerParty,
          splitwellCodegen.GroupInvite.COMPANION,
        )
      } yield {
        val filtered = groupInvites.filter(c => c.hasStakeholder(userParty))
        v0.ListGroupInvitesResponse(
          filtered.map(c => Contract.fromCodegenContract(c, None).toProtoV0)
        )
      }
    }

  override def listAcceptedGroupInvites(
      request: v0.ListAcceptedGroupInvitesRequest
  ): Future[v0.ListAcceptedGroupInvitesResponse] =
    withSpanFromGrpcContext("GrpcSplitwellService") { implicit traceContext => span =>
      val userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
      for {
        acceptedGroupInvites <- connection.activeContracts(
          splitwellDomainId,
          providerParty,
          splitwellCodegen.AcceptedGroupInvite.COMPANION,
        )
      } yield {
        val filtered =
          acceptedGroupInvites.filter(c =>
            c.hasStakeholder(userParty) &&
              c.data.groupKey == groupKey(userParty, providerParty, request.groupId)
          )
        v0.ListAcceptedGroupInvitesResponse(
          filtered.map(c => Contract.fromCodegenContract(c, None).toProtoV0)
        )
      }
    }

  override def listBalanceUpdates(
      request: v0.ListBalanceUpdatesRequest
  ): Future[v0.ListBalanceUpdatesResponse] =
    withSpanFromGrpcContext("GrpcSplitwellService") { implicit traceContext => span =>
      val userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
      for {
        balanceUpdates <- connection.activeContracts(
          splitwellDomainId,
          providerParty,
          splitwellCodegen.BalanceUpdate.COMPANION,
        )
      } yield {
        val filtered = balanceUpdates.filter(c =>
          c.hasStakeholder(userParty) &&
            new splitwellCodegen.GroupKey(
              c.data.group.owner,
              c.data.group.provider,
              c.data.group.id,
            ) == groupKey(
              request.getGroupKey
            )
        )
        v0.ListBalanceUpdatesResponse(
          filtered.map(c => Contract.fromCodegenContract(c, None).toProtoV0)
        )
      }
    }

  override def listBalances(
      request: v0.ListBalancesRequest
  ): Future[v0.ListBalancesResponse] =
    withSpanFromGrpcContext("GrpcSplitwellService") { implicit traceContext => span =>
      val userParty = Proto.tryDecode(Proto.Party)(request.getContext.userPartyId)
      val javaUserParty = userParty.toProtoPrimitive
      for {
        balanceUpdates <- connection.activeContracts(
          splitwellDomainId,
          providerParty,
          splitwellCodegen.BalanceUpdate.COMPANION,
        )
      } yield {
        val filtered = balanceUpdates
          .filter(c =>
            c.hasStakeholder(userParty) &&
              new splitwellCodegen.GroupKey(
                c.data.group.owner,
                c.data.group.provider,
                c.data.group.id,
              ) == groupKey(
                request.getGroupKey
              )
          )
          .map(_.data)
        def combine(
            acc: Map[String, BigDecimal],
            update: splitwellCodegen.BalanceUpdate,
        ): Map[String, BigDecimal] =
          update.update match {
            case externalPayment: splitwellCodegen.balanceupdatetype.ExternalPayment => {
              val split: BigDecimal =
                BigDecimal(externalPayment.amount) / (update.group.members.size + 1)
              if (externalPayment.payer == javaUserParty) {
                (update.group.owner +: update.group.members.asScala).foldLeft(acc) {
                  case (acc, member) =>
                    if (member == externalPayment.payer) acc
                    else
                      acc.updatedWith(member)(prev => Some(prev.getOrElse[BigDecimal](0.0) + split))
                }
              } else if (
                (update.group.owner +: update.group.members.asScala).contains(javaUserParty)
              ) {
                acc.updatedWith(externalPayment.payer)(prev =>
                  Some(prev.getOrElse[BigDecimal](0.0) - split)
                )
              } else {
                acc
              }
            }
            case transfer: splitwellCodegen.balanceupdatetype.Transfer =>
              if (transfer.sender == javaUserParty) {
                acc.updatedWith(transfer.receiver)(prev =>
                  Some(prev.getOrElse[BigDecimal](0.0) + transfer.amount)
                )
              } else if (transfer.receiver == javaUserParty) {
                acc.updatedWith(transfer.sender)(prev =>
                  Some(prev.getOrElse[BigDecimal](0.0) - transfer.amount)
                )
              } else acc
            case netting: splitwellCodegen.balanceupdatetype.Netting =>
              netting.balanceChanges.asScala
                .getOrElse(javaUserParty, new java.util.HashMap[String, java.math.BigDecimal])
                .asScala
                .iterator
                .foldLeft(acc) { case (acc, (k, v)) =>
                  acc.updatedWith(k)(prev => Some(prev.getOrElse[BigDecimal](0.0) + v))
                }
            case _ =>
              throw new IllegalArgumentException(s"Invalid balance update type: ${update.update}")
          }
        val balances: Map[String, BigDecimal] =
          filtered.foldLeft(Map.empty[String, BigDecimal])(combine)
        v0.ListBalancesResponse(balances.map { case (k, v) => k -> Proto.encode(v) })
      }
    }

  override def getProviderPartyId(request: Empty): Future[v0.GetProviderPartyIdResponse] =
    withSpanFromGrpcContext("GrpcSplitwellService") { implicit traceContext => span =>
      Future.successful(v0.GetProviderPartyIdResponse(Proto.encode(providerParty)))
    }

  override def listConnectedDomains(request: Empty): Future[v0.ListConnectedDomainsResponse] =
    withSpanFromGrpcContext("GrpcSplitwellService") { implicit traceContext => span =>
      for {
        domains <- store.domains.listConnectedDomains()
      } yield {
        v0.ListConnectedDomainsResponse(Some(Proto.encode(domains)))
      }
    }

  override def getSplitwellDomainId(request: Empty): Future[v0.GetSplitwellDomainIdResponse] =
    withSpanFromGrpcContext("GrpcSplitwellService") { implicit traceContext => span =>
      Future.successful(
        v0.GetSplitwellDomainIdResponse(splitwellDomainId.toProtoPrimitive)
      )
    }

  private def groupKey(key: v0.GroupKey): splitwellCodegen.GroupKey =
    new splitwellCodegen.GroupKey(
      key.ownerPartyId,
      key.providerPartyId,
      new splitwellCodegen.GroupId(key.id),
    )

  private def groupKey(owner: PartyId, provider: PartyId, id: String): splitwellCodegen.GroupKey =
    new splitwellCodegen.GroupKey(
      owner.toProtoPrimitive,
      provider.toProtoPrimitive,
      new splitwellCodegen.GroupId(id),
    )
}

object GrpcSplitwellService {
  implicit class ContractSyntax(private val contract: CodegenContract[_, _]) extends AnyVal {
    def hasStakeholder(party: PartyId): Boolean = {
      val p = party.toProtoPrimitive
      contract.signatories.contains(p) || contract.observers.contains(p)
    }
  }
}
