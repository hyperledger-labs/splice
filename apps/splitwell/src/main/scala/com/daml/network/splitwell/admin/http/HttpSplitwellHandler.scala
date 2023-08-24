package com.daml.network.splitwell.admin.http

import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.splitwell.admin.api.client.commands.HttpSplitwellAppClient.SplitwellDomains
import com.daml.network.http.v0.{definitions, splitwell as v0}
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.store.MultiDomainAcsStore.ContractState
import com.daml.network.util.{Codec, ContractWithState}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpSplitwellHandler(
    participantAdminConnection: ParticipantAdminConnection,
    splitwellDomains: SplitwellDomains,
    providerParty: PartyId,
    store: SplitwellStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SplitwellHandler[Unit]
    with Spanning
    with NamedLogging {

  import HttpSplitwellHandler.*

  def listGroups(respond: v0.SplitwellResource.ListGroupsResponse.type)(
      party: String
  )(extracted: Unit): scala.concurrent.Future[v0.SplitwellResource.ListGroupsResponse] =
    withNewTrace("HttpSplitwellHandler") { implicit traceContext => _ =>
      val userParty = Codec.tryDecode(Codec.Party)(party)
      for {
        // TODO(M4-02): check (or simulate check) of the user's cross-participant access token
        groups <- store.listGroups(userParty)
      } yield {
        definitions.ListGroupsResponse(groups.map(encodeContractWithState).toVector)
      }
    }

  def listGroupInvites(respond: v0.SplitwellResource.ListGroupInvitesResponse.type)(
      party: String
  )(extracted: Unit): scala.concurrent.Future[v0.SplitwellResource.ListGroupInvitesResponse] =
    withNewTrace("HttpSplitwellHandler") { implicit traceContext => _ =>
      val userParty = Codec.tryDecode(Codec.Party)(party)
      for {
        groupInvites <- store.listGroupInvites(userParty)
      } yield {
        definitions.ListGroupInvitesResponse(
          groupInvites.map(encodeContractWithState).toVector
        )
      }
    }

  def listAcceptedGroupInvites(
      respond: v0.SplitwellResource.ListAcceptedGroupInvitesResponse.type
  )(party: String, groupId: String)(
      extracted: Unit
  ): Future[v0.SplitwellResource.ListAcceptedGroupInvitesResponse] =
    withNewTrace("HttpSplitwellHandler") { implicit traceContext => _ =>
      val userParty = Codec.tryDecode(Codec.Party)(party)
      for {
        acceptedGroupInvites <- store.listAcceptedGroupInvites(userParty, groupId)
      } yield {
        definitions.ListAcceptedGroupInvitesResponse(
          acceptedGroupInvites.map(_.contract.toHttp).toVector
        )
      }
    }

  def listBalanceUpdates(
      respond: v0.SplitwellResource.ListBalanceUpdatesResponse.type
  )(party: String, groupId: String, ownerPartyId: String)(
      extracted: Unit
  ): Future[v0.SplitwellResource.ListBalanceUpdatesResponse] =
    withNewTrace("HttpSplitwellHandler") { implicit traceContext => _ =>
      val userParty = Codec.tryDecode(Codec.Party)(party)
      for {
        balanceUpdates <- store.listBalanceUpdates(userParty, groupKey(groupId, ownerPartyId))
      } yield {
        definitions.ListBalanceUpdatesResponse(
          balanceUpdates
            .map(_.contract.toHttp)
            .toVector
        )
      }
    }

  def listBalances(
      respond: v0.SplitwellResource.ListBalancesResponse.type
  )(party: String, groupId: String, ownerPartyId: String)(
      extracted: Unit
  ): Future[v0.SplitwellResource.ListBalancesResponse] =
    withNewTrace("HttpSplitwellHandler") { implicit traceContext => _ =>
      val userParty = Codec.tryDecode(Codec.Party)(party)
      val javaUserParty = userParty.toProtoPrimitive
      for {
        balanceUpdates <- store.listBalanceUpdates(userParty, groupKey(groupId, ownerPartyId))
      } yield {
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
          balanceUpdates.map(_.payload).foldLeft(Map.empty[String, BigDecimal])(combine)
        definitions.ListBalancesResponse(balances.map { case (k, v) => k -> Codec.encode(v) })
      }
    }

  def listSplitwellInstalls(
      respond: v0.SplitwellResource.ListSplitwellInstallsResponse.type
  )(party: String)(extracted: Unit): Future[v0.SplitwellResource.ListSplitwellInstallsResponse] =
    withNewTrace("HttpSplitwellHandler") { implicit traceContext => _ =>
      val userParty = Codec.tryDecode(Codec.Party)(party)
      for {
        installs <- store.listSplitwellInstalls(userParty)
      } yield {
        definitions.ListSplitwellInstallsResponse(
          installs
            .map(c =>
              definitions.SplitwellInstall(
                Codec.encodeContractId(c.contractId),
                Codec.encode(c.domain),
              )
            )
            .toVector
        )
      }
    }

  def listSplitwellRules(
      respond: v0.SplitwellResource.ListSplitwellRulesResponse.type
  )()(extracted: Unit): Future[v0.SplitwellResource.ListSplitwellRulesResponse] =
    withNewTrace("HttpSplitwellHandler") { implicit traceContext => _ =>
      for {
        rules <- store.listSplitwellRules()
      } yield {
        definitions.ListSplitwellRulesResponse(
          rules.map(_.toHttp).toVector
        )
      }
    }

  override def getProviderPartyId(
      respond: v0.SplitwellResource.GetProviderPartyIdResponse.type
  )()(fake: Unit): Future[v0.SplitwellResource.GetProviderPartyIdResponse] =
    withNewTrace("HttpSplitwellHandler") { _ => _ =>
      Future.successful(definitions.GetProviderPartyIdResponse(Codec.encode(providerParty)))
    }

  override def getSplitwellDomainIds(
      respond: v0.SplitwellResource.GetSplitwellDomainIdsResponse.type
  )()(fake: Unit): Future[v0.SplitwellResource.GetSplitwellDomainIdsResponse] =
    withNewTrace("HttpSplitwellHandler") { _ => _ =>
      Future.successful(
        definitions.GetSplitwellDomainIdsResponse(
          splitwellDomains.preferred.toProtoPrimitive,
          splitwellDomains.others.map(_.toProtoPrimitive).toVector,
        )
      )
    }

  def getConnectedDomains(respond: v0.SplitwellResource.GetConnectedDomainsResponse.type)(
      party: String
  )(extracted: Unit): scala.concurrent.Future[v0.SplitwellResource.GetConnectedDomainsResponse] =
    withNewTrace("HttpSplitwellHandler") { implicit traceContext => _ =>
      for {
        mappings <- participantAdminConnection.listPartyToParticipant(
          filterParty = party
        )
      } yield definitions.GetConnectedDomainsResponse(
        mappings
          .map(_.base.domain)
          .filter(storeId =>
            // While the field is called `domain` this is really a store id not a domain id.
            // We only want domain stores so we filter to store ids that are domain ids.
            Codec.decode(Codec.DomainId)(storeId).isRight
          )
          .toVector
      )
    }

  private def groupKey(groupId: String, ownerPartyId: String): splitwellCodegen.GroupKey =
    new splitwellCodegen.GroupKey(
      ownerPartyId,
      providerParty.toProtoPrimitive,
      new splitwellCodegen.GroupId(groupId),
    )

}

object HttpSplitwellHandler {
  private def encodeContractWithState(
      cws: ContractWithState[?, ?]
  )(implicit elc: ErrorLoggingContext): definitions.ContractWithState = {
    import ContractState.*
    definitions.ContractWithState(
      cws.contract.toHttp,
      cws.state match {
        case Assigned(domain) => Some(domain.toProtoPrimitive)
        case InFlight => None
      },
    )
  }
}
