package com.daml.network.validator.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.http.v0.validator as http
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

  case object GetValidatorUserInfo
      extends BaseCommand[http.GetValidatorUserInfoResponse, UserInfo] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
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
  case object Register extends BaseCommand[http.RegisterResponse, PartyId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.RegisterResponse] =
      client.register(headers = headers)

    override def handleResponse(
        response: http.RegisterResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, PartyId] = {
      response match {
        case http.RegisterResponse.OK(response) =>
          PartyId.fromProtoPrimitive(response.partyId)
      }
    }
  }
}
