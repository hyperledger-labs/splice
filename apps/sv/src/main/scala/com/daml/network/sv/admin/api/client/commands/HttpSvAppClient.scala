package com.daml.network.sv.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.util.ByteString
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.codegen.java.cc.coin.CoinRules
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.http.v0.{definitions, sv as http}
import com.daml.network.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.topology.{ParticipantId, PartyId}

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

object HttpSvAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.SvClient(host)
  }

  final case class DebugInfo(
      svUser: String,
      svParty: PartyId,
      svcParty: PartyId,
      coinRules: CoinRules.ContractId,
      svcRules: SvcRules.ContractId,
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

    override def handleResponse(response: http.OnboardValidatorResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = response match {
      case http.OnboardValidatorResponse.OK => Right(())
      case http.OnboardValidatorResponse.BadRequest(e) => Left(e.error)
      case http.OnboardValidatorResponse.Unauthorized(e) => Left(e.error)
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

    override def handleResponse(response: http.StartSvOnboardingResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = response match {
      case http.StartSvOnboardingResponse.OK => Right(())
      case http.StartSvOnboardingResponse.BadRequest(e) => Left(e.error)
      case http.StartSvOnboardingResponse.Unauthorized(e) => Left(e.error)
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

    override def handleResponse(
        response: http.GetSvOnboardingStatusResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, SvOnboardingStatus] = {
      response match {
        case http.GetSvOnboardingStatusResponse.OK(response) =>
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
        case http.GetSvOnboardingStatusResponse.BadRequest(e) => Left(e.error)
        case http.GetSvOnboardingStatusResponse.InternalServerError(e) => Left(e.error)
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

    override def handleResponse(response: http.DevNetOnboardValidatorPrepareResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, String] = response match {
      case http.DevNetOnboardValidatorPrepareResponse.OK(secret) => Right(secret)
      case http.DevNetOnboardValidatorPrepareResponse.InternalServerError(e) => Left(e.error)
      case http.DevNetOnboardValidatorPrepareResponse.NotImplemented(e) => Left(e.error)
    }
  }

  case object GetDebugInfo extends BaseCommand[http.GetDebugInfoResponse, DebugInfo] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetDebugInfoResponse] =
      client.getDebugInfo(headers = headers)

    override def handleResponse(
        response: http.GetDebugInfoResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, DebugInfo] = {
      response match {
        case http.GetDebugInfoResponse.OK(
              definitions.GetDebugInfoResponse(
                svUser,
                svPartyId,
                svcPartyId,
                coinRulesContractId,
                svcRulesContractId,
              )
            ) =>
          for {
            svPartyId <- PartyId.fromProtoPrimitive(svPartyId)
            svcPartyId <- PartyId.fromProtoPrimitive(svcPartyId)
          } yield DebugInfo(
            svUser,
            svPartyId,
            svcPartyId,
            new CoinRules.ContractId(coinRulesContractId),
            new SvcRules.ContractId(svcRulesContractId),
          )
      }
    }
  }

  case class OnboardSvPartyMigrationAuthorize(participantId: ParticipantId)
      extends BaseCommand[http.OnboardSvPartyMigrationAuthorizeResponse, ByteString] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.OnboardSvPartyMigrationAuthorizeResponse] =
      client.onboardSvPartyMigrationAuthorize(
        body = definitions.OnboardSvPartyMigrationAuthorizeRequest(participantId.toProtoPrimitive),
        headers = headers,
      )

    override def handleResponse(
        response: http.OnboardSvPartyMigrationAuthorizeResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, ByteString] = {
      response match {
        case http.OnboardSvPartyMigrationAuthorizeResponse.OK(
              definitions.OnboardSvPartyMigrationAuthorizeResponse(encodedAcsSnapshot)
            ) =>
          Right(ByteString(Base64.getDecoder.decode(encodedAcsSnapshot)))
        case http.OnboardSvPartyMigrationAuthorizeResponse.BadRequest(e) => Left(e.error)
        case http.OnboardSvPartyMigrationAuthorizeResponse.Unauthorized(e) => Left(e.error)
      }
    }
  }
}
