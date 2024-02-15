package com.daml.network.sv.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.HttpClientBuilder
import com.daml.network.codegen.java.cc.coinrules.CoinRules
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.environment.RetryProvider.QuietNonRetryableException
import com.daml.network.http.v0.{definitions, sv as http}
import com.daml.network.sv.http.SvHttpClient.BaseCommand
import com.daml.network.util.{Codec, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.domain.sequencing.sequencer.SequencerSnapshot as CantonSequencerSnapshot
import com.digitalasset.canton.topology.admin.v30
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.topology.{MediatorId, ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

object HttpSvAppClient {
  final case class SvcInfo(
      svUser: String,
      svParty: PartyId,
      svcParty: PartyId,
      votingThreshold: BigInt,
      latestMiningRound: Contract[OpenMiningRound.ContractId, OpenMiningRound],
      coinRules: Contract[CoinRules.ContractId, CoinRules],
      svcRules: Contract[SvcRules.ContractId, SvcRules],
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
        svcRulesCid: SvcRules.ContractId,
    ) extends SvOnboardingStatus
  }

  case class OnboardValidator(candidate: PartyId, secret: String)
      extends BaseCommand[http.OnboardValidatorResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardValidatorResponse] =
      client.onboardValidator(
        body = definitions.OnboardValidatorRequest(candidate.toProtoPrimitive, secret),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.OnboardValidatorResponse.OK =>
      Right(())
    }
  }

  case class StartSvOnboarding(token: String)
      extends BaseCommand[http.StartSvOnboardingResponse, Unit] {

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
      extends BaseCommand[http.GetSvOnboardingStatusResponse, SvOnboardingStatus] {

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
              svcRulesCid <- Codec
                .decodeJavaContractId(SvcRules.COMPANION)(status.contractId)
            } yield SvOnboardingStatus.Completed(
              status.name,
              svcRulesCid,
            )
          case definitions.GetSvOnboardingStatusResponse.members.SvOnboardingStateUnknown(_) =>
            Right(SvOnboardingStatus.Unknown())
        }
    }
  }

  case class DevNetOnboardValidatorPrepare()
      extends BaseCommand[http.DevNetOnboardValidatorPrepareResponse, String] {

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

  case object GetSvcInfo extends BaseCommand[http.GetSvcInfoResponse, SvcInfo] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetSvcInfoResponse] =
      client.getSvcInfo(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.GetSvcInfoResponse.OK(
            definitions.GetSvcInfoResponse(
              svUser,
              svPartyId,
              svcPartyId,
              votingThreshold,
              latestMiningRound,
              coinRules,
              svcRules,
            )
          ) =>
        for {
          svPartyId <- Codec.decode(Codec.Party)(svPartyId)
          svcPartyId <- Codec.decode(Codec.Party)(svcPartyId)
          latestMiningRound <- Contract
            .fromHttp(OpenMiningRound.COMPANION)(latestMiningRound)
            .left
            .map(_.toString)
          coinRules <- Contract.fromHttp(CoinRules.COMPANION)(coinRules).left.map(_.toString)
          svcRules <- Contract.fromHttp(SvcRules.COMPANION)(svcRules).left.map(_.toString)
        } yield SvcInfo(
          svUser,
          svPartyId,
          svcPartyId,
          votingThreshold,
          latestMiningRound,
          coinRules,
          svcRules,
        )
    }
  }

  case class SequencerSnapshot(
      topologySnapshot: GenericStoredTopologyTransactionsX,
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
  ) extends BaseCommand[
        http.OnboardSvPartyMigrationAuthorizeResponse,
        Either[
          OnboardSvPartyMigrationAuthorizeProposalNotFound,
          OnboardSvPartyMigrationAuthorizeResponse,
        ],
      ] {

    override def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): http.SvClient =
      http.SvClient.httpClient(
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
  ) extends BaseCommand[
        http.OnboardSvSequencerResponse,
        SequencerSnapshot,
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
      case http.OnboardSvSequencerResponse.OK(definitions.OnboardSvSequencerResponse(snapshot)) =>
        (for {
          topologySnapshot <- StoredTopologyTransactionsX
            .fromProtoV30(
              v30.TopologyTransactions.parseFrom(
                Base64.getDecoder().decode(snapshot.topologySnapshot)
              )
            )
          sequencerSnapshot <- CantonSequencerSnapshot
            .fromByteArrayUnsafe(Base64.getDecoder().decode(snapshot.sequencerSnapshot))
        } yield SequencerSnapshot(topologySnapshot, sequencerSnapshot)).left.map(_.message)
    }
  }

  case class OnboardSvMediator(
      mediatorId: MediatorId
  ) extends BaseCommand[
        http.OnboardSvMediatorResponse,
        Unit,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.OnboardSvMediatorResponse] =
      client.onboardSvMediator(
        body = definitions.OnboardSvMediatorRequest(
          Codec.encode(mediatorId)
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = _ => Right(())
  }

  case class GetCometBftNodeStatus()
      extends BaseCommand[
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
  ) extends BaseCommand[
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
