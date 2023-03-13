package com.daml.network.validator.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.http.v0.definitions.OnboardUserRequest
import com.daml.network.http.v0.validatorAdmin as http
import com.daml.network.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.http.v0.validatorAdmin.OffboardUserResponse.NotFound

object HttpValidatorAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ValidatorAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ValidatorAdminClient(host)
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
          Codec.decode(Codec.Party)(response.partyId)
      }
    }
  }

  case object ListUsers extends BaseCommand[http.ListUsersResponse, Seq[String]] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListUsersResponse] =
      client.listUsers(headers = headers)

    override def handleResponse(
        response: http.ListUsersResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, Seq[String]] = {
      response match {
        case http.ListUsersResponse.OK(response) =>
          Right(response.usernames.toSeq)
      }
    }
  }

  case class OffboardUser(username: String) extends BaseCommand[http.OffboardUserResponse, Unit] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OffboardUserResponse] =
      client.offboardUser(username, headers)

    override def handleResponse(
        response: http.OffboardUserResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, Unit] = {
      response match {
        case http.OffboardUserResponse.OK =>
          Right(())
        case NotFound(value) =>
          Left(value.error)
      }
    }
  }
}
