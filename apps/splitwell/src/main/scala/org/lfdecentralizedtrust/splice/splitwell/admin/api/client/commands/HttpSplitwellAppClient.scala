// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.HttpHeader
import org.apache.pekko.stream.Materializer
import cats.implicits.*
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.splitwell as http
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  Codec,
  Contract,
  ContractWithState,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.topology.{SynchronizerId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.ExecutionContext

object HttpSplitwellAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SplitwellClient

    def createClient(host: String, clientName: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.SplitwellClient.httpClient(
        HttpClientBuilder().buildClient(clientName, commandName),
        host,
      )
  }

  case class ListGroups(party: PartyId)
      extends BaseCommand[http.ListGroupsResponse, Seq[
        ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]
      ]] {

    override def submitRequest(client: Client, headers: List[HttpHeader]) =
      client.listGroups(Codec.encode(party), headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListGroupsResponse.OK(response) =>
        response.groups
          .traverse {
            contractWithStateFromJson(Contract.fromHttp(splitwellCodegen.Group.COMPANION))
          }
          .leftMap(_.toString)
    }
  }

  private[HttpSplitwellAppClient] def contractWithStateFromJson[TCid, T](
      decodeContract: definitions.Contract => Either[ProtoDeserializationError, Contract[TCid, T]]
  )(encG: definitions.ContractWithState) = {
    for {
      contract <- decodeContract(encG.contract)
      synchronizerId <- encG.domainId
        .traverse(SynchronizerId.fromProtoPrimitive(_, "domain_id"))
    } yield ContractWithState(
      contract,
      synchronizerId.fold(ContractState.InFlight: ContractState)(ContractState.Assigned.apply),
    )
  }

  case class ListGroupInvites(party: PartyId)
      extends BaseCommand[
        http.ListGroupInvitesResponse,
        Seq[
          ContractWithState[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]
        ],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.listGroupInvites(Codec.encode(party), headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListGroupInvitesResponse.OK(response) =>
        response.groupInvites
          .traverse(
            contractWithStateFromJson(Contract.fromHttp(splitwellCodegen.GroupInvite.COMPANION))
          )
          .leftMap(_.toString)
    }
  }

  case class ListAcceptedGroupInvites(
      party: PartyId,
      id: String,
  ) extends BaseCommand[
        http.ListAcceptedGroupInvitesResponse,
        Seq[Contract[
          splitwellCodegen.AcceptedGroupInvite.ContractId,
          splitwellCodegen.AcceptedGroupInvite,
        ]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.listAcceptedGroupInvites(Codec.encode(party), id, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListAcceptedGroupInvitesResponse.OK(response) =>
        response.acceptedGroupInvites
          .traverse(Contract.fromHttp(splitwellCodegen.AcceptedGroupInvite.COMPANION)(_))
          .leftMap(_.toString)
    }
  }

  case class ListBalanceUpdates(
      party: PartyId,
      key: GroupKey,
  ) extends BaseCommand[http.ListBalanceUpdatesResponse, Seq[
        ContractWithState[splitwellCodegen.BalanceUpdate.ContractId, splitwellCodegen.BalanceUpdate]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.listBalanceUpdates(Codec.encode(party), key.id, Codec.encode(key.owner), headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListBalanceUpdatesResponse.OK(response) =>
        response.balanceUpdates
          .traverse(ContractWithState.fromHttp(splitwellCodegen.BalanceUpdate.COMPANION)(_))
          .leftMap(_.toString)
    }
  }

  case class ListBalances(
      party: PartyId,
      key: GroupKey,
  ) extends BaseCommand[http.ListBalancesResponse, Map[PartyId, BigDecimal]] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.listBalances(Codec.encode(party), key.id, Codec.encode(key.owner), headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListBalancesResponse.OK(response) =>
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
  }

  case class GetProviderPartyId(
  ) extends BaseCommand[http.GetProviderPartyIdResponse, PartyId] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.getProviderPartyId(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetProviderPartyIdResponse.OK(response) =>
        Codec.decode(Codec.Party)(response.providerPartyId)
    }
  }

  case class SplitwellDomains(
      preferred: SynchronizerId,
      others: Seq[SynchronizerId],
  )
  case class GetSplitwellDomainIds(
  ) extends BaseCommand[http.GetSplitwellDomainIdsResponse, SplitwellDomains] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.getSplitwellDomainIds(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetSplitwellDomainIdsResponse.OK(response) =>
        for {
          preferred <- Codec.decode(Codec.SynchronizerId)(response.preferred)
          others <- response.otherDomainIds.traverse(id => Codec.decode(Codec.SynchronizerId)(id))
        } yield SplitwellDomains(preferred, others)
    }
  }

  case class GetConnectedDomains(
      partyId: PartyId
  ) extends BaseCommand[http.GetConnectedDomainsResponse, Seq[
        SynchronizerId
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.getConnectedDomains(Codec.encode(partyId), headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetConnectedDomainsResponse.OK(response) =>
        response.domainIds.traverse(id => Codec.decode(Codec.SynchronizerId)(id))
    }
  }

  case class ListSplitwellInstalls(partyId: PartyId)
      extends BaseCommand[
        http.ListSplitwellInstallsResponse,
        Map[SynchronizerId, splitwellCodegen.SplitwellInstall.ContractId],
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.listSplitwellInstalls(Codec.encode(partyId), headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListSplitwellInstallsResponse.OK(response) =>
        for {
          installs <- response.installs.traverse(install =>
            for {
              domain <- Codec.decode(Codec.SynchronizerId)(install.domainId)
              cid <- Codec.decodeJavaContractId(splitwellCodegen.SplitwellInstall.COMPANION)(
                install.contractId
              )
            } yield domain -> cid
          )
        } yield installs.toMap
    }
  }

  case object ListSplitwellRules
      extends BaseCommand[
        http.ListSplitwellRulesResponse,
        Seq[AssignedContract[
          splitwellCodegen.SplitwellRules.ContractId,
          splitwellCodegen.SplitwellRules,
        ]],
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.listSplitwellRules(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListSplitwellRulesResponse.OK(response) =>
        response.rules.traverse(
          AssignedContract.fromHttp(splitwellCodegen.SplitwellRules.COMPANION)
        )
    }
  }

  final case class GroupKey(
      id: String,
      owner: PartyId,
  )
}
