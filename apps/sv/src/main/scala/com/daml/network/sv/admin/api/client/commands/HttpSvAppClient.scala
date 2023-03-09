package com.daml.network.sv.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.{Materializer}
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.codegen.java.cc.coin.CoinRules
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.http.v0.{definitions, sv as http}
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

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

  case object ListOngoingValidatorOnboardings
      extends BaseCommand[http.ListOngoingValidatorOnboardingsResponse, Seq[
        Contract[ValidatorOnboarding.ContractId, ValidatorOnboarding]
      ]] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListOngoingValidatorOnboardingsResponse] =
      client.listOngoingValidatorOnboardings(headers = headers)

    override def handleResponse(response: http.ListOngoingValidatorOnboardingsResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[Contract[ValidatorOnboarding.ContractId, ValidatorOnboarding]]] =
      response match {
        case http.ListOngoingValidatorOnboardingsResponse.OK(response) =>
          response.ongoingValidatorOnboardings
            .traverse(req => Contract.fromJson(ValidatorOnboarding.COMPANION)(req))
            .leftMap(_.toString)
      }
  }

  case class PrepareValidatorOnboarding(expiresIn: FiniteDuration)
      extends BaseCommand[http.PrepareValidatorOnboardingResponse, String] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.PrepareValidatorOnboardingResponse] =
      client.prepareValidatorOnboarding(
        body = definitions.PrepareValidatorOnboardingRequest(expiresIn.toSeconds),
        headers = headers,
      )

    override def handleResponse(response: http.PrepareValidatorOnboardingResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, String] = response match {
      case http.PrepareValidatorOnboardingResponse.OK(
            definitions.PrepareValidatorOnboardingResponse(secret)
          ) =>
        Right(secret)
      case http.PrepareValidatorOnboardingResponse.InternalServerError(e) => Left(e)
    }
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
      case http.OnboardValidatorResponse.BadRequest(e) => Left(e)
      case http.OnboardValidatorResponse.Unauthorized(e) => Left(e)
    }
  }

  case class OnboardSv(token: String) extends BaseCommand[http.OnboardSvResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardSvResponse] =
      client.onboardSv(
        body = definitions.OnboardSvRequest(token),
        headers = headers,
      )

    override def handleResponse(response: http.OnboardSvResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = response match {
      case http.OnboardSvResponse.OK => Right(())
      case http.OnboardSvResponse.BadRequest(e) => Left(e)
      case http.OnboardSvResponse.Unauthorized(e) => Left(e)
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
      case http.DevNetOnboardValidatorPrepareResponse.InternalServerError(e) => Left(e)
      case http.DevNetOnboardValidatorPrepareResponse.NotImplemented(e) => Left(e)
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
}
