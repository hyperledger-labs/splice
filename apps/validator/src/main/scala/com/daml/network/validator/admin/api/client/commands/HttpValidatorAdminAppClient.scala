package com.daml.network.validator.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.http.v0.validatorAdmin as http
import com.daml.network.http.v0.definitions.OnboardUserRequest
import com.daml.network.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import java.util.Base64
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
        HttpClientBuilder().buildClient,
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

  // TODO(#6177) Extend by "user->party" mappings and consider renaming to `ParticipantIdentitiesDump`
  final case class ParticipantIdentity(
      id: ParticipantId,
      keys: Seq[ParticipantKey],
      bootstrapTxes: Seq[Array[Byte]],
  )
  final case class ParticipantKey(
      keyPair: Array[Byte],
      name: Option[String],
  )

  case class ExportParticipantIdentity()
      extends BaseCommand[
        http.ExportParticipantIdentityResponse,
        ParticipantIdentity,
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ExportParticipantIdentityResponse] =
      client.exportParticipantIdentity(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ExportParticipantIdentityResponse.OK(response) =>
        for {
          id <- ParticipantId.fromProtoPrimitive(response.id, "participant").left.map(_.message)
        } yield ParticipantIdentity(
          id = id,
          keys = response.keys.toSeq.map(k =>
            ParticipantKey(Base64.getDecoder.decode(k.keyPair), k.name)
          ),
          bootstrapTxes = response.bootstrapTxes.toSeq.map(t => Base64.getDecoder.decode(t)),
        )
    }
  }
}
