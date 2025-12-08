// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client.commands

import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.synchronizer.sequencer.SequencerSnapshot as CantonSequencerSnapshot
import com.digitalasset.canton.topology.store.StoredTopologyTransactions.GenericStoredTopologyTransactions
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes}
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpClientBuilder
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvNodeState
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding.{
  SvOnboardingConfirmed,
  SvOnboardingRequest,
}
import org.lfdecentralizedtrust.splice.environment.RetryProvider.QuietNonRetryableException
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.{definitions, sv_public as http}
import org.lfdecentralizedtrust.splice.sv.http.SvHttpClient.BaseCommandPublic
import org.lfdecentralizedtrust.splice.util.{Codec, ContractWithState, TemplateJsonDecoder}

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

object HttpSvPublicAppClient {
  final case class DsoInfo(
      svUser: String,
      svParty: PartyId,
      dsoParty: PartyId,
      votingThreshold: BigInt,
      latestMiningRound: ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
      amuletRules: ContractWithState[AmuletRules.ContractId, AmuletRules],
      dsoRules: ContractWithState[DsoRules.ContractId, DsoRules],
      svNodeStates: Map[PartyId, ContractWithState[SvNodeState.ContractId, SvNodeState]],
      initialRound: Option[String],
  )

  sealed trait SvOnboardingStatus
  object SvOnboardingStatus {
    final case class Unknown() extends SvOnboardingStatus
    final case class Requested(
        name: String,
        svOnboardingRequestCid: SvOnboardingRequest.ContractId,
        confirmedBy: Vector[String],
        requiredNumConfirmations: Int,
    ) extends SvOnboardingStatus
    final case class Confirmed(
        name: String,
        svOnboardingConfirmedCid: SvOnboardingConfirmed.ContractId,
    ) extends SvOnboardingStatus
    final case class Completed(
        name: String,
        dsoRulesCid: DsoRules.ContractId,
    ) extends SvOnboardingStatus
  }

  case class OnboardValidator(
      candidate: PartyId,
      secret: String,
      version: String,
      contactPoint: String,
  ) extends BaseCommandPublic[http.OnboardValidatorResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardValidatorResponse] =
      client.onboardValidator(
        body = definitions.OnboardValidatorRequest(
          candidate.toProtoPrimitive,
          secret,
          Some(version),
          Some(contactPoint),
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.OnboardValidatorResponse.OK =>
      Right(())
    }
  }

  case class StartSvOnboarding(token: String)
      extends BaseCommandPublic[http.StartSvOnboardingResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.StartSvOnboardingResponse] =
      client.startSvOnboarding(
        body = definitions.StartSvOnboardingRequest(token),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.StartSvOnboardingResponse.OK =>
      Right(())
    }
  }

  case class getSvOnboardingStatus(candidate: String)
      extends BaseCommandPublic[http.GetSvOnboardingStatusResponse, SvOnboardingStatus] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetSvOnboardingStatusResponse] = {
      client.getSvOnboardingStatus(candidatePartyIdOrName = candidate, headers = headers)
    }

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[http.GetSvOnboardingStatusResponse, Either[String, SvOnboardingStatus]] = {
      case http.GetSvOnboardingStatusResponse.OK(response) =>
        response match {
          case definitions.GetSvOnboardingStatusResponse.members
                .SvOnboardingStateRequested(status) =>
            for {
              svOnboardingRequestCid <- Codec.decodeJavaContractId(SvOnboardingRequest.COMPANION)(
                status.contractId
              )
            } yield SvOnboardingStatus.Requested(
              status.name,
              svOnboardingRequestCid,
              status.confirmedBy,
              status.requiredNumConfirmations,
            )
          case definitions.GetSvOnboardingStatusResponse.members
                .SvOnboardingStateConfirmed(status) =>
            for {
              svOnboardingConfirmedCid <- Codec
                .decodeJavaContractId(SvOnboardingConfirmed.COMPANION)(status.contractId)
            } yield SvOnboardingStatus.Confirmed(
              status.name,
              svOnboardingConfirmedCid,
            )
          case definitions.GetSvOnboardingStatusResponse.members
                .SvOnboardingStateCompleted(status) =>
            for {
              dsoRulesCid <- Codec
                .decodeJavaContractId(DsoRules.COMPANION)(status.contractId)
            } yield SvOnboardingStatus.Completed(
              status.name,
              dsoRulesCid,
            )
          case definitions.GetSvOnboardingStatusResponse.members.SvOnboardingStateUnknown(_) =>
            Right(SvOnboardingStatus.Unknown())
        }
    }
  }

  case class DevNetOnboardValidatorPrepare()
      extends BaseCommandPublic[http.DevNetOnboardValidatorPrepareResponse, String] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.DevNetOnboardValidatorPrepareResponse] =
      client.devNetOnboardValidatorPrepare(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.DevNetOnboardValidatorPrepareResponse.OK(secret) =>
      Right(secret)
    }
  }

  case object GetDsoInfo extends BaseCommandPublic[http.GetDsoInfoResponse, DsoInfo] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetDsoInfoResponse] =
      client.getDsoInfo(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.GetDsoInfoResponse.OK(
            definitions.GetDsoInfoResponse(
              svUser,
              svPartyId,
              dsoPartyId,
              votingThreshold,
              latestMiningRound,
              amuletRules,
              dsoRules,
              svNodeStates,
              initialRound,
            )
          ) =>
        for {
          svPartyId <- Codec.decode(Codec.Party)(svPartyId)
          dsoPartyId <- Codec.decode(Codec.Party)(dsoPartyId)
          latestMiningRound <- ContractWithState
            .fromHttp(OpenMiningRound.COMPANION)(latestMiningRound)
            .leftMap(_.toString)
          amuletRules <- ContractWithState
            .fromHttp(AmuletRules.COMPANION)(amuletRules)
            .left
            .map(_.toString)
          dsoRules <- ContractWithState.fromHttp(DsoRules.COMPANION)(dsoRules).left.map(_.toString)
          svNodeStates <- svNodeStates.traverse { co =>
            for {
              nodeState <- ContractWithState
                .fromHttp(SvNodeState.COMPANION)(co)
                .left
                .map(_.toString)
              partyId <- Codec.decode(Codec.Party)(nodeState.payload.sv)
            } yield partyId -> nodeState
          }
        } yield DsoInfo(
          svUser,
          svPartyId,
          dsoPartyId,
          votingThreshold,
          latestMiningRound,
          amuletRules,
          dsoRules,
          svNodeStates.toMap,
          initialRound,
        )
    }
  }

  case class SequencerSnapshot(
      topologySnapshot: GenericStoredTopologyTransactions,
      sequencerSnapshot: CantonSequencerSnapshot,
  )

  case class OnboardSvPartyMigrationAuthorizeProposalNotFound(
      partyToParticipantMappingSerial: PositiveInt
  ) extends QuietNonRetryableException(
        s"Party migration failed as required proposals were not found. Found base mappings: PartyToParticipant($partyToParticipantMappingSerial)"
      )
  case class OnboardSvPartyMigrationAuthorizeResponse(
      acsSnapshot: ByteString
  )

  case class OnboardSvPartyMigrationAuthorize(
      participantId: ParticipantId,
      candidate: PartyId,
  ) extends BaseCommandPublic[
        http.OnboardSvPartyMigrationAuthorizeResponse,
        Either[
          OnboardSvPartyMigrationAuthorizeProposalNotFound,
          OnboardSvPartyMigrationAuthorizeResponse,
        ],
      ] {

    override def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): http.SvPublicClient =
      http.SvPublicClient.httpClient(
        HttpClientBuilder().buildClient(Set(StatusCodes.BadRequest)),
        host,
      )

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.OnboardSvPartyMigrationAuthorizeResponse] =
      client.onboardSvPartyMigrationAuthorize(
        body = definitions.OnboardSvPartyMigrationAuthorizeRequest(
          candidate.toProtoPrimitive
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.OnboardSvPartyMigrationAuthorizeResponse.BadRequest(
            definitions.OnboardSvPartyMigrationAuthorizeErrorResponse.members
              .AcceptedStateNotFoundErrorResponse(
                response
              )
          ) =>
        Left(response.acceptedStateNotFound.error)
      case http.OnboardSvPartyMigrationAuthorizeResponse.BadRequest(
            definitions.OnboardSvPartyMigrationAuthorizeErrorResponse.members
              .ProposalNotFoundErrorResponse(
                response
              )
          ) =>
        Right(
          Left(
            OnboardSvPartyMigrationAuthorizeProposalNotFound(
              PositiveInt.tryCreate(response.proposalNotFound.partyToParticipantBaseSerial.intValue)
            )
          )
        )
      case http.OnboardSvPartyMigrationAuthorizeResponse.OK(
            definitions.OnboardSvPartyMigrationAuthorizeResponse(
              encodedAcsSnapshot
            )
          ) =>
        Right(
          Right(
            OnboardSvPartyMigrationAuthorizeResponse(
              ByteString.copyFrom(Base64.getDecoder.decode(encodedAcsSnapshot))
            )
          )
        )
    }
  }

  case class OnboardSvSequencer(
      sequencerId: SequencerId
  ) extends BaseCommandPublic[
        http.OnboardSvSequencerResponse,
        ByteString,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.OnboardSvSequencerResponse] =
      client.onboardSvSequencer(
        body = definitions.OnboardSvSequencerRequest(
          Codec.encode(sequencerId)
        ),
        headers = headers,
      )

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.OnboardSvSequencerResponse.OK(
            definitions.OnboardSvSequencerResponse(onboardingState)
          ) =>
        Right(ByteString.copyFrom(Base64.getDecoder().decode(onboardingState)))
    }
  }

  case class GetCometBftNodeStatus()
      extends BaseCommandPublic[
        http.GetCometBftNodeStatusResponse,
        definitions.CometBftNodeStatusResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetCometBftNodeStatusResponse] =
      client.getCometBftNodeStatus(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      http.GetCometBftNodeStatusResponse,
      Either[String, definitions.CometBftNodeStatusResponse],
    ] = {
      case http.GetCometBftNodeStatusResponse.OK(
            definitions.CometBftNodeStatusOrErrorResponse.members
              .CometBftNodeStatusResponse(response)
          ) =>
        Right(response)
      case http.GetCometBftNodeStatusResponse.OK(
            definitions.CometBftNodeStatusOrErrorResponse.members.ErrorResponse(response)
          ) =>
        Left(response.error)
    }
  }

  case class CometBftJsonRpcRequest(
      id: definitions.CometBftJsonRpcRequestId,
      method: definitions.CometBftJsonRpcRequest.Method,
      params: Map[String, io.circe.Json],
  ) extends BaseCommandPublic[
        http.CometBftJsonRpcRequestResponse,
        definitions.CometBftJsonRpcResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.CometBftJsonRpcRequestResponse] =
      client.cometBftJsonRpcRequest(
        headers = headers,
        body = definitions.CometBftJsonRpcRequest(id, method, Some(params)),
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      http.CometBftJsonRpcRequestResponse,
      Either[String, definitions.CometBftJsonRpcResponse],
    ] = {
      case http.CometBftJsonRpcRequestResponse.OK(
            definitions.CometBftJsonRpcOrErrorResponse.members.CometBftJsonRpcResponse(response)
          ) =>
        Right(response)
      case http.CometBftJsonRpcRequestResponse.OK(
            definitions.CometBftJsonRpcOrErrorResponse.members.ErrorResponse(response)
          ) =>
        Left(response.error)
      case http.CometBftJsonRpcRequestResponse.NotFound(response) => Left(response.error)
    }
  }

}
