package com.daml.network.admin.api.client

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT

import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.environment.CNNodeStatus
import com.daml.network.http.v0.{commonAdmin as http, definitions}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.tracing.TraceContext
import java.time.OffsetDateTime
import scala.concurrent.{ExecutionContext, Future}

object HttpAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.CommonAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.CommonAdminClient.httpClient(
        HttpClientBuilder().buildClient,
        host,
      )
  }

  case class GetHealthStatus[S <: NodeStatus.Status](
      deserialize: definitions.Status => Either[String, S]
  ) extends BaseCommand[http.GetHealthStatusResponse, NodeStatus[S]] {
    override def submitRequest(
        client: http.CommonAdminClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetHealthStatusResponse] =
      client.getHealthStatus(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetHealthStatusResponse.OK(response) =>
      CNNodeStatus.fromJsonNodeStatus(deserialize)(response)
    }
  }

  case class VersionInfo(version: String, commitTs: OffsetDateTime)

  case class GetVersion() extends BaseCommand[http.GetVersionResponse, VersionInfo] {
    override def submitRequest(
        client: http.CommonAdminClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetVersionResponse] =
      client.getVersion(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetVersionResponse.OK(response) =>
        Right(VersionInfo(response.version, response.commitTs))
    }
  }
}
