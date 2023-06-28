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
import scala.util.Try

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

  final case class ParticipantIdentitiesDump(
      id: ParticipantId,
      keys: Seq[ParticipantKey],
      bootstrapTxes: Seq[Array[Byte]],
      users: Seq[ParticipantUser],
  )
  final case class ParticipantKey(
      keyPair: Array[Byte],
      name: Option[String],
  )
  final case class ParticipantUser(
      id: String,
      primaryParty: Option[PartyId],
  )

  case class DumpParticipantIdentities()
      extends BaseCommand[
        http.DumpParticipantIdentitiesResponse,
        ParticipantIdentitiesDump,
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.DumpParticipantIdentitiesResponse] =
      client.dumpParticipantIdentities(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.DumpParticipantIdentitiesResponse.OK(response) =>
        Try(
          ParticipantIdentitiesDump(
            id = ParticipantId.tryFromProtoPrimitive(response.id),
            keys = response.keys.toSeq.map(k =>
              ParticipantKey(Base64.getDecoder.decode(k.keyPair), k.name)
            ),
            bootstrapTxes = response.bootstrapTxes.toSeq.map(t => Base64.getDecoder.decode(t)),
            users = response.users.toSeq.map(user =>
              ParticipantUser(user.id, user.primaryParty.map(PartyId.tryFromProtoPrimitive(_)))
            ),
          )
        ).toEither.left.map(_.getMessage())
    }
  }
}
