// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules as amuletrulesCodegen
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.{definitions, validator_admin as http}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.util.{
  Codec,
  Contract,
  ContractWithState,
  TemplateJsonDecoder,
}
import org.lfdecentralizedtrust.splice.validator.migration.DomainMigrationDump
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{SynchronizerId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes}
import org.apache.pekko.stream.Materializer

import java.time.{Instant, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

object HttpValidatorAdminAppClient {
  val clientName = "HttpValidatorAdminAppClient"

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ValidatorAdminClient

    def createClient(host: String, clientName: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ValidatorAdminClient.httpClient(
        HttpClientBuilder().buildClient(clientName, commandName, Set(StatusCodes.NotFound)),
        host,
      )
  }

  case class GenerateExternalPartyTopology(partyHint: String, publicKey: String)
      extends BaseCommand[
        http.GenerateExternalPartyTopologyResponse,
        definitions.GenerateExternalPartyTopologyResponse,
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
      definitions.GenerateExternalPartyTopologyResponse,
    ]] = { case http.GenerateExternalPartyTopologyResponse.OK(response) =>
      Right(response)
    }
  }

  case class SubmitExternalPartyTopology(
      topologyTx: Vector[definitions.SignedTopologyTx],
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
    ): PartialFunction[http.SubmitExternalPartyTopologyResponse, Either[String, PartyId]] = {
      case http.SubmitExternalPartyTopologyResponse.OK(response) =>
        Codec.decode(Codec.Party)(response.partyId)
    }
  }

  case class OnboardUser(
      name: String,
      existingPartyId: Option[PartyId],
      createIfMissing: Option[Boolean] = None,
  ) extends BaseCommand[http.OnboardUserResponse, PartyId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardUserResponse] =
      client.onboardUser(
        definitions
          .OnboardUserRequest(name, existingPartyId.map(_.toProtoPrimitive), createIfMissing),
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
        amuletrulesCodegen.ExternalPartySetupProposal.ContractId,
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
      amuletrulesCodegen.ExternalPartySetupProposal.ContractId,
    ]] = { case http.CreateExternalPartySetupProposalResponse.OK(response) =>
      Codec.decodeJavaContractId(amuletrulesCodegen.ExternalPartySetupProposal.COMPANION)(
        response.contractId
      )
    }
  }

  case class ListExternalPartySetupProposals()
      extends BaseCommand[
        http.ListExternalPartySetupProposalsResponse,
        Seq[ContractWithState[
          amuletrulesCodegen.ExternalPartySetupProposal.ContractId,
          amuletrulesCodegen.ExternalPartySetupProposal,
        ]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListExternalPartySetupProposalsResponse] =
      client.listExternalPartySetupProposals(headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListExternalPartySetupProposalsResponse.OK(response) =>
      response.contracts.traverse(contractWithState =>
        for {
          contract <- Contract
            .fromHttp(amuletrulesCodegen.ExternalPartySetupProposal.COMPANION)(
              contractWithState.contract
            )
            .leftMap(_.toString)
          synchronizerId <- contractWithState.domainId.traverse(SynchronizerId.fromString)
        } yield ContractWithState(
          contract,
          synchronizerId.fold(ContractState.InFlight: ContractState)(ContractState.Assigned.apply),
        )
      )
    }
  }

  case class PrepareAcceptExternalPartySetupProposal(
      contractId: amuletrulesCodegen.ExternalPartySetupProposal.ContractId,
      userPartyId: PartyId,
      verboseHashing: Boolean,
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
          Some(verboseHashing),
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
        (amuletrulesCodegen.TransferPreapproval.ContractId, String),
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
      Codec
        .decodeJavaContractId(amuletrulesCodegen.TransferPreapproval.COMPANION)(
          response.transferPreapprovalContractId
        )
        .map(cid => (cid, response.updateId))
    }
  }

  case class LookupTransferPreapprovalByParty(
      userPartyId: PartyId
  ) extends BaseCommand[
        http.LookupTransferPreapprovalByPartyResponse,
        Option[ContractWithState[
          amuletrulesCodegen.TransferPreapproval.ContractId,
          amuletrulesCodegen.TransferPreapproval,
        ]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.LookupTransferPreapprovalByPartyResponse] =
      client.lookupTransferPreapprovalByParty(userPartyId.toProtoPrimitive, headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.LookupTransferPreapprovalByPartyResponse.OK(response) =>
        ContractWithState
          .fromHttp(amuletrulesCodegen.TransferPreapproval.COMPANION)(response.transferPreapproval)
          .map(Some(_))
          .leftMap(_.toString)
      case http.LookupTransferPreapprovalByPartyResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class CancelTransferPreapprovalByParty(
      userPartyId: PartyId
  ) extends BaseCommand[http.CancelTransferPreapprovalByPartyResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.cancelTransferPreapprovalByParty(userPartyId.toProtoPrimitive, headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.CancelTransferPreapprovalByPartyResponse.OK =>
        Right(())
      case http.CancelTransferPreapprovalByPartyResponse.NotFound(response) =>
        Left(response.error)
    }
  }

  case class ListTransferPreapprovals()
      extends BaseCommand[
        http.ListTransferPreapprovalsResponse,
        Seq[ContractWithState[
          amuletrulesCodegen.TransferPreapproval.ContractId,
          amuletrulesCodegen.TransferPreapproval,
        ]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListTransferPreapprovalsResponse] =
      client.listTransferPreapprovals(headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListTransferPreapprovalsResponse.OK(response) =>
      response.contracts.traverse(contractWithState =>
        for {
          contract <- Contract
            .fromHttp(amuletrulesCodegen.TransferPreapproval.COMPANION)(
              contractWithState.contract
            )
            .leftMap(_.toString)
          synchronizerId <- contractWithState.domainId.traverse(SynchronizerId.fromString)
        } yield ContractWithState(
          contract,
          synchronizerId.fold(ContractState.InFlight: ContractState)(ContractState.Assigned.apply),
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
      expiresAt: CantonTimestamp,
      nonce: Long,
      description: Option[String],
      verboseHashing: Boolean,
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
          expiresAt.toInstant.atOffset(ZoneOffset.UTC),
          nonce,
          Some(verboseHashing),
          description = description,
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
        String,
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
    ) = { case http.SubmitTransferPreapprovalSendResponse.OK(response) => Right(response.updateId) }
  }

}
