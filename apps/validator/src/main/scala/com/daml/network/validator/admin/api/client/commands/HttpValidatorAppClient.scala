package com.daml.network.validator.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.http.v0.definitions.OnboardUserRequest
import com.daml.network.http.v0.validator as http
import com.daml.network.util.TemplateJsonDecoder
import com.daml.network.validator.admin.api.client.UserInfo
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.topology.{DomainId, PartyId}

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
  case class OnboardUser(name: String) extends BaseCommand[http.OnboardUserResponse, PartyId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
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

  case object ListConnectedDomains
      extends BaseCommand[http.ListConnectedDomainsResponse, Map[DomainAlias, DomainId]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.listConnectedDomains(headers)

    override def handleResponse(
        response: http.ListConnectedDomainsResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, Map[DomainAlias, DomainId]] =
      response match {
        case http.ListConnectedDomainsResponse.OK(response) =>
          response.connectedDomains.toList
            .traverse { case (k, v) =>
              for {
                k <- DomainAlias.create(k)
                v <- DomainId.fromString(v)
              } yield (k, v)
            }
            .map(_.toMap)
      }
  }
}
