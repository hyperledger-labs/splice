package com.daml.network.validator.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.http.v0.validator_public as http
import com.daml.network.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

final case class UserInfo(
    primaryParty: PartyId,
    userName: String,
    featured: Boolean,
)

object HttpValidatorPublicAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ValidatorPublicClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ValidatorPublicClient.httpClient(
        HttpClientBuilder().buildClient(),
        host,
      )
  }

  case object GetValidatorUserInfo
      extends BaseCommand[http.GetValidatorUserInfoResponse, UserInfo] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetValidatorUserInfoResponse] =
      client.getValidatorUserInfo(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetValidatorUserInfoResponse.OK(response) =>
      Codec
        .decode(Codec.Party)(response.partyId)
        .map(pid => UserInfo(pid, response.userName, response.featured))
    }
  }
}
