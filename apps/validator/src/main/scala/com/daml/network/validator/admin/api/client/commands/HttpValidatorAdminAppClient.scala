package com.daml.network.validator.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.http.v0.validator_admin as http
import com.daml.network.http.v0.definitions.OnboardUserRequest
import com.daml.network.util.{Codec, NodeIdentitiesDump, TemplateJsonDecoder}
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

object HttpValidatorAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ValidatorAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ValidatorAdminClient.httpClient(
        HttpClientBuilder().buildClient(),
        host,
      )
  }

  case class OnboardUser(name: String) extends BaseCommand[http.OnboardUserResponse, PartyId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardUserResponse] =
      client.onboardUser(OnboardUserRequest(name), headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.OnboardUserResponse.OK(response) =>
      Codec.decode(Codec.Party)(response.partyId)
    }
  }

  case object ListUsers extends BaseCommand[http.ListUsersResponse, Seq[String]] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListUsersResponse] =
      client.listUsers(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListUsersResponse.OK(response) => Right(response.usernames.toSeq)
    }
  }

  case class OffboardUser(username: String) extends BaseCommand[http.OffboardUserResponse, Unit] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OffboardUserResponse] =
      client.offboardUser(username, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.OffboardUserResponse.OK => Right(())
    }
  }

  case class DumpParticipantIdentities()
      extends BaseCommand[
        http.DumpParticipantIdentitiesResponse,
        NodeIdentitiesDump,
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.DumpParticipantIdentitiesResponse] =
      client.dumpParticipantIdentities(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.DumpParticipantIdentitiesResponse.OK(response) =>
        NodeIdentitiesDump.fromHttp(ParticipantId.tryFromProtoPrimitive, response)
    }
  }
}
