// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.admin.api.client.commands

import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.splice.transferpreapproval as transferPreapprovalCodegen
import com.daml.network.codegen.java.splice.wallet.externalparty as externalPartyCodegen
import com.daml.network.http.HttpClient
import com.daml.network.http.v0.definitions.{
  GenerateExternalPartyTopologyResponse,
  SignedTopologyTx,
}
import com.daml.network.http.v0.validator_admin.SubmitExternalPartyTopologyResponse
import com.daml.network.http.v0.{definitions, validator_admin as http}
import com.daml.network.identities.NodeIdentitiesDump
import com.daml.network.store.MultiDomainAcsStore.ContractState
import com.daml.network.util.{Codec, Contract, ContractWithState, TemplateJsonDecoder}
import com.daml.network.validator.migration.DomainMigrationDump
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

object HttpValidatorAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ValidatorAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ValidatorAdminClient.httpClient(
        HttpClientBuilder().buildClient(),
        host,
      )
  }

  case class GenerateExternalPartyTopology(partyHint: String, publicKey: String)
      extends BaseCommand[
        http.GenerateExternalPartyTopologyResponse,
        GenerateExternalPartyTopologyResponse,
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GenerateExternalPartyTopologyResponse] =
      client.generateExternalPartyTopology(
        definitions.GenerateExternalPartyTopologyRequest(partyHint, publicKey),
        headers,
      )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[http.GenerateExternalPartyTopologyResponse, Either[
      String,
      GenerateExternalPartyTopologyResponse,
    ]] = { case http.GenerateExternalPartyTopologyResponse.OK(response) =>
      Right(response)
    }
  }

  case class SubmitExternalPartyTopology(
      topologyTx: Vector[SignedTopologyTx],
      publicKey: String,
  ) extends BaseCommand[http.SubmitExternalPartyTopologyResponse, PartyId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.SubmitExternalPartyTopologyResponse] =
      client.submitExternalPartyTopology(
        definitions
          .SubmitExternalPartyTopologyRequest(publicKey, topologyTx),
        headers,
      )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[SubmitExternalPartyTopologyResponse, Either[String, PartyId]] = {
      case http.SubmitExternalPartyTopologyResponse.OK(response) =>
        Codec.decode(Codec.Party)(response.partyId)
    }
  }

  case class OnboardUser(name: String, existingPartyId: Option[PartyId])
      extends BaseCommand[http.OnboardUserResponse, PartyId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardUserResponse] =
      client.onboardUser(
        definitions.OnboardUserRequest(name, existingPartyId.map(_.toProtoPrimitive)),
        headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.OnboardUserResponse.OK(response) =>
      Codec.decode(Codec.Party)(response.partyId)
    }
  }

  case object ListUsers extends BaseCommand[http.ListUsersResponse, Seq[String]] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListUsersResponse] =
      client.listUsers(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListUsersResponse.OK(response) => Right(response.usernames.toSeq)
    }
  }

  case class OffboardUser(username: String) extends BaseCommand[http.OffboardUserResponse, Unit] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OffboardUserResponse] =
      client.offboardUser(username, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.OffboardUserResponse.OK => Right(())
    }
  }

  case class DumpParticipantIdentities()
      extends BaseCommand[
        http.DumpParticipantIdentitiesResponse,
        NodeIdentitiesDump,
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.DumpParticipantIdentitiesResponse] =
      client.dumpParticipantIdentities(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.DumpParticipantIdentitiesResponse.OK(response) =>
        NodeIdentitiesDump.fromHttp(ParticipantId.tryFromProtoPrimitive, response)
    }
  }

  case class GetValidatorDomainDataSnapshot(
      timestamp: Instant,
      migrationId: Option[Long],
      force: Boolean,
  ) extends BaseCommand[
        http.GetValidatorDomainDataSnapshotResponse,
        DomainMigrationDump,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetValidatorDomainDataSnapshotResponse] =
      client.getValidatorDomainDataSnapshot(
        timestamp.toString,
        migrationId = migrationId,
        force = Some(force),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetValidatorDomainDataSnapshotResponse.OK(response) =>
      DomainMigrationDump.fromHttp(response.dataSnapshot)
    }
  }

  case class GetDecentralizedSynchronizerConnectionConfig()
      extends BaseCommand[
        http.GetDecentralizedSynchronizerConnectionConfigResponse,
        definitions.GetDecentralizedSynchronizerConnectionConfigResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetDecentralizedSynchronizerConnectionConfigResponse] =
      client.getDecentralizedSynchronizerConnectionConfig(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      http.GetDecentralizedSynchronizerConnectionConfigResponse,
      Either[String, definitions.GetDecentralizedSynchronizerConnectionConfigResponse],
    ] = { case http.GetDecentralizedSynchronizerConnectionConfigResponse.OK(response) =>
      Right(response)
    }
  }

  case class CreateExternalPartySetupProposal(partyId: PartyId)
      extends BaseCommand[
        http.CreateExternalPartySetupProposalResponse,
        externalPartyCodegen.ExternalPartySetupProposal.ContractId,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.CreateExternalPartySetupProposalResponse] =
      client.createExternalPartySetupProposal(
        definitions.CreateExternalPartySetupProposalRequest(partyId.toProtoPrimitive),
        headers = headers,
      )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[http.CreateExternalPartySetupProposalResponse, Either[
      String,
      externalPartyCodegen.ExternalPartySetupProposal.ContractId,
    ]] = { case http.CreateExternalPartySetupProposalResponse.OK(response) =>
      Codec.decodeJavaContractId(externalPartyCodegen.ExternalPartySetupProposal.COMPANION)(
        response.contractId
      )
    }
  }

  case class ListExternalPartySetupProposals()
      extends BaseCommand[
        http.ListExternalPartySetupProposalResponse,
        Seq[ContractWithState[
          externalPartyCodegen.ExternalPartySetupProposal.ContractId,
          externalPartyCodegen.ExternalPartySetupProposal,
        ]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListExternalPartySetupProposalResponse] =
      client.listExternalPartySetupProposal(headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListExternalPartySetupProposalResponse.OK(response) =>
      response.contracts.traverse(contractWithState =>
        for {
          contract <- Contract
            .fromHttp(externalPartyCodegen.ExternalPartySetupProposal.COMPANION)(
              contractWithState.contract
            )
            .leftMap(_.toString)
          domainId <- contractWithState.domainId.traverse(DomainId.fromString)
        } yield ContractWithState(
          contract,
          domainId.fold(ContractState.InFlight: ContractState)(ContractState.Assigned),
        )
      )
    }
  }

  case class PrepareAcceptExternalPartySetupProposal(
      contractId: externalPartyCodegen.ExternalPartySetupProposal.ContractId,
      userPartyId: PartyId,
  ) extends BaseCommand[
        http.PrepareAcceptExternalPartySetupProposalResponse,
        definitions.PrepareAcceptExternalPartySetupProposalResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.PrepareAcceptExternalPartySetupProposalResponse] =
      client.prepareAcceptExternalPartySetupProposal(
        definitions.PrepareAcceptExternalPartySetupProposalRequest(
          contractId.contractId,
          userPartyId.toProtoPrimitive,
        ),
        headers = headers,
      )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.PrepareAcceptExternalPartySetupProposalResponse.OK(response) =>
      Right(response)
    }
  }

  case class SubmitAcceptExternalPartySetupProposal(
      userPartyId: PartyId,
      transaction: String,
      signature: String,
      publicKey: String,
  ) extends BaseCommand[
        http.SubmitAcceptExternalPartySetupProposalResponse,
        transferPreapprovalCodegen.TransferPreapproval.ContractId,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.SubmitAcceptExternalPartySetupProposalResponse] =
      client.submitAcceptExternalPartySetupProposal(
        definitions.SubmitAcceptExternalPartySetupProposalRequest(
          definitions.ExternalPartySubmission(
            userPartyId.toProtoPrimitive,
            transaction,
            signature,
            publicKey,
          )
        ),
        headers = headers,
      )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.SubmitAcceptExternalPartySetupProposalResponse.OK(response) =>
      Codec.decodeJavaContractId(transferPreapprovalCodegen.TransferPreapproval.COMPANION)(
        response.transferPreapprovalContractId
      )
    }
  }

  case class ListTransferPreapprovals()
      extends BaseCommand[
        http.ListTransferPreapprovalResponse,
        Seq[ContractWithState[
          transferPreapprovalCodegen.TransferPreapproval.ContractId,
          transferPreapprovalCodegen.TransferPreapproval,
        ]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListTransferPreapprovalResponse] =
      client.listTransferPreapproval(headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListTransferPreapprovalResponse.OK(response) =>
      response.contracts.traverse(contractWithState =>
        for {
          contract <- Contract
            .fromHttp(transferPreapprovalCodegen.TransferPreapproval.COMPANION)(
              contractWithState.contract
            )
            .leftMap(_.toString)
          domainId <- contractWithState.domainId.traverse(DomainId.fromString)
        } yield ContractWithState(
          contract,
          domainId.fold(ContractState.InFlight: ContractState)(ContractState.Assigned),
        )
      )
    }
  }

  final case class GetExternalPartyBalance(partyId: PartyId)
      extends BaseCommand[
        http.GetExternalPartyBalanceResponse,
        definitions.ExternalPartyBalanceResponse,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetExternalPartyBalanceResponse] =
      client.getExternalPartyBalance(Codec.encode(partyId), headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetExternalPartyBalanceResponse.OK(response) =>
      Right(response)
    }
  }

  case class PrepareTransferPreapprovalSend(
      senderPartyId: PartyId,
      receiverPartyId: PartyId,
      amount: BigDecimal,
  ) extends BaseCommand[
        http.PrepareTransferPreapprovalSendResponse,
        definitions.PrepareTransferPreapprovalSendResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.PrepareTransferPreapprovalSendResponse] =
      client.prepareTransferPreapprovalSend(
        definitions.PrepareTransferPreapprovalSendRequest(
          senderPartyId.toProtoPrimitive,
          receiverPartyId.toProtoPrimitive,
          amount,
        ),
        headers = headers,
      )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.PrepareTransferPreapprovalSendResponse.OK(response) =>
      Right(response)
    }
  }

  case class SubmitTransferPreapprovalSend(
      senderPartyId: PartyId,
      transaction: String,
      signature: String,
      publicKey: String,
  ) extends BaseCommand[
        http.SubmitTransferPreapprovalSendResponse,
        Unit,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.SubmitTransferPreapprovalSendResponse] =
      client.submitTransferPreapprovalSend(
        definitions.SubmitTransferPreapprovalSendRequest(
          definitions.ExternalPartySubmission(
            senderPartyId.toProtoPrimitive,
            transaction,
            signature,
            publicKey,
          )
        ),
        headers = headers,
      )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.SubmitTransferPreapprovalSendResponse.OK => Right(()) }
  }

}
