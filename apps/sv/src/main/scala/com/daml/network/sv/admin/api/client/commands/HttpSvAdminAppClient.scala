package com.daml.network.sv.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import cats.implicits.{toBifunctorOps, toTraverseOps}
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.http.v0.{definitions, svAdmin as http}
import com.daml.network.util.{Contract, TemplateJsonDecoder}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

object HttpSvAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.SvAdminClient(host)
  }

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
      case http.PrepareValidatorOnboardingResponse.InternalServerError(e) =>
        Left(e.error)
    }
  }

  case class ApproveSvIdentity(candidateName: String, candidateKey: String)
      extends BaseCommand[http.ApproveSvIdentityResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ApproveSvIdentityResponse] =
      client.approveSvIdentity(
        body = definitions.ApproveSvIdentityRequest(candidateName, candidateKey),
        headers = headers,
      )

    override def handleResponse(response: http.ApproveSvIdentityResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = response match {
      case http.ApproveSvIdentityResponse.OK => Right(())
      case http.ApproveSvIdentityResponse.BadRequest(e) => Left(e.error)
    }
  }

}
