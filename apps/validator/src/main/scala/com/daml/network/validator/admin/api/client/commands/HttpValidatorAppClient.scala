package com.daml.network.validator.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.http.v0.validator as http
import com.daml.network.http.v0.definitions.OnboardUserRequest
import com.daml.network.util.TemplateJsonDecoder
import com.daml.network.validator.admin.api.client.UserInfo
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

object HttpValidatorAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ValidatorClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ValidatorClient(host)
  }

  case class GetValidatorUserInfo(headers: List[HttpHeader])
      extends BaseCommand[http.GetValidatorUserInfoResponse, UserInfo] {

    def submitRequest(
        client: Client
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetValidatorUserInfoResponse] =
      client.getValidatorUserInfo(headers = headers)

    override def handleResponse(
        response: http.GetValidatorUserInfoResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, UserInfo] = {
      response match {
        case http.GetValidatorUserInfoResponse.OK(response) =>
          PartyId.fromProtoPrimitive(response.partyId).map(pid => UserInfo(pid, response.userName))
      }
    }
  }
  case class OnboardUser(name: String, headers: List[HttpHeader])
      extends BaseCommand[http.OnboardUserResponse, PartyId] {

    def submitRequest(
        client: Client
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardUserResponse] =
      client.onboardUser(OnboardUserRequest(name), headers)

    override def handleResponse(
        response: http.OnboardUserResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, PartyId] = {
      response match {
        case http.OnboardUserResponse.OK(response) =>
          PartyId.fromProtoPrimitive(response.partyId)
      }
    }
  }
}
