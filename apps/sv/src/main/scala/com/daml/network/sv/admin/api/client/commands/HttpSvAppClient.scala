package com.daml.network.sv.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.cc.coin.CoinRules
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.http.v0.{definitions, sv as http}
import com.daml.network.util.{TemplateJsonDecoder, Contract, Codec}
import com.google.protobuf.ByteString
import com.digitalasset.canton.domain.sequencing.sequencer.{
  SequencerSnapshot as CantonSequencerSnapshot
}
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.protocol.v0
import com.digitalasset.canton.topology.{MediatorId, ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.tracing.TraceContext

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

object HttpSvAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = {
      http.SvClient.httpClient(HttpClientBuilder().buildClient, host)
    }
  }

  final case class SvcInfo(
      svUser: String,
      svParty: PartyId,
      svcParty: PartyId,
      coinRules: Contract[CoinRules.ContractId, CoinRules],
      svcRules: Contract[SvcRules.ContractId, SvcRules],
  )

  sealed trait SvOnboardingStatus;
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
    ) = { case http.GetSvOnboardingStatusResponse.OK(response) =>
      (response.state match {
        case "unknown" => Some(SvOnboardingStatus.Unknown())
        case "requested" =>
          for {
            name <- response.name
            cidString <- response.contractId
            svOnboardingRequestCid <- Codec
              .decodeJavaContractId(SvOnboardingRequest.COMPANION)(cidString)
              .toOption
            confirmedBy <- response.confirmedBy
            requiredNumConfirmations <- response.requiredNumConfirmations
          } yield SvOnboardingStatus.Requested(
            name,
            svOnboardingRequestCid,
            confirmedBy,
            requiredNumConfirmations,
          )
        case "confirmed" =>
          for {
            name <- response.name
            cidString <- response.contractId
            svOnboardingConfirmedCid <- Codec
              .decodeJavaContractId(SvOnboardingConfirmed.COMPANION)(cidString)
              .toOption
          } yield SvOnboardingStatus.Confirmed(
            name,
            svOnboardingConfirmedCid,
          )
        case "completed" =>
          for {
            name <- response.name
            cidString <- response.contractId
            svcRulesCid <- Codec
              .decodeJavaContractId(SvcRules.COMPANION)(cidString)
              .toOption
          } yield SvOnboardingStatus.Completed(
            name,
            svcRulesCid,
          )
        case _ => None
      }) match {
        case Some(status) => Right(status: SvOnboardingStatus)
        case None => Left(s"Could not parse response: $response.")
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
              coinRules,
              svcRules,
            )
          ) =>
        for {
          svPartyId <- Codec.decode(Codec.Party)(svPartyId)
          svcPartyId <- Codec.decode(Codec.Party)(svcPartyId)
          coinRules <- Contract.fromJson(CoinRules.COMPANION)(coinRules).left.map(_.toString)
          svcRules <- Contract.fromJson(SvcRules.COMPANION)(svcRules).left.map(_.toString)
        } yield SvcInfo(
          svUser,
          svPartyId,
          svcPartyId,
          coinRules,
          svcRules,
        )
    }
  }

  case class SequencerSnapshot(
      topologySnapshot: GenericStoredTopologyTransactionsX,
      sequencerSnapshot: CantonSequencerSnapshot,
      staticDomainParameters: StaticDomainParameters,
  )

  case class OnboardSvPartyMigrationAuthorizeResponse(
      acsSnapshot: ByteString,
      sequencerSnapshot: Option[SequencerSnapshot],
  )

  case class OnboardSvPartyMigrationAuthorize(
      participantId: ParticipantId,
      sequencerId: Option[SequencerId],
      mediatorId: Option[MediatorId],
      candidate: PartyId,
  ) extends BaseCommand[
        http.OnboardSvPartyMigrationAuthorizeResponse,
        OnboardSvPartyMigrationAuthorizeResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.OnboardSvPartyMigrationAuthorizeResponse] =
      client.onboardSvPartyMigrationAuthorize(
        body = definitions.OnboardSvPartyMigrationAuthorizeRequest(
          participantId.toProtoPrimitive,
          sequencerId.map(_.toProtoPrimitive),
          mediatorId.map(_.toProtoPrimitive),
          candidate.toProtoPrimitive,
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.OnboardSvPartyMigrationAuthorizeResponse.OK(
            definitions.OnboardSvPartyMigrationAuthorizeResponse(
              encodedAcsSnapshot,
              sequencerSnapshot,
            )
          ) =>
        for {
          sequencerSnapshot <- sequencerSnapshot
            .traverse { snapshot =>
              for {
                topologySnapshot <- StoredTopologyTransactionsX
                  .fromProtoV0(
                    v0.TopologyTransactions.parseFrom(
                      Base64.getDecoder().decode(snapshot.topologySnapshot)
                    )
                  )
                sequencerSnapshot <- CantonSequencerSnapshot
                  .fromByteArray(Base64.getDecoder().decode(snapshot.sequencerSnapshot))
                staticDomainParameters <- StaticDomainParameters.fromByteArray(
                  Base64.getDecoder().decode(snapshot.staticDomainParameters)
                )
              } yield SequencerSnapshot(topologySnapshot, sequencerSnapshot, staticDomainParameters)
            }
            .left
            .map(_.message)
        } yield OnboardSvPartyMigrationAuthorizeResponse(
          ByteString.copyFrom(Base64.getDecoder.decode(encodedAcsSnapshot)),
          sequencerSnapshot,
        )
    }
  }
}
