package com.daml.network.admin.api.client

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT

import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.environment.CNNodeStatus
import com.daml.network.http.v0.definitions
import com.daml.network.http.v0.external.commonAdmin as externalHttp
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.tracing.TraceContext
import java.time.OffsetDateTime
import scala.concurrent.{ExecutionContext, Future}

object HttpAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = externalHttp.CommonAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      externalHttp.CommonAdminClient.httpClient(
        HttpClientBuilder().buildClient,
        host,
      )
  }

  case class GetHealthStatus[S <: NodeStatus.Status](
      deserialize: definitions.Status => Either[String, S]
  ) extends BaseCommand[externalHttp.GetHealthStatusResponse, NodeStatus[S]] {
    override def submitRequest(
        client: externalHttp.CommonAdminClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.GetHealthStatusResponse] =
      client.getHealthStatus(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.GetHealthStatusResponse.OK(response) =>
      CNNodeStatus.fromJsonNodeStatus(deserialize)(response)
    }
  }

  case class VersionInfo(version: String, commitTs: OffsetDateTime)

  case class GetVersion() extends BaseCommand[externalHttp.GetVersionResponse, VersionInfo] {
    override def submitRequest(
        client: externalHttp.CommonAdminClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.GetVersionResponse] =
      client.getVersion(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case externalHttp.GetVersionResponse.OK(response) =>
        Right(VersionInfo(response.version, response.commitTs))
    }
  }

  case object IsLive extends BaseCommand[externalHttp.IsLiveResponse, Boolean] {
    override def submitRequest(
        client: externalHttp.CommonAdminClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.IsLiveResponse] =
      client.isLive(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case externalHttp.IsLiveResponse.OK =>
        Right(true)
      case externalHttp.IsLiveResponse.ServiceUnavailable =>
        Right(false)
    }
  }
  case object IsReady extends BaseCommand[externalHttp.IsReadyResponse, Boolean] {
    override def submitRequest(
        client: externalHttp.CommonAdminClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.IsReadyResponse] =
      client.isReady(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case externalHttp.IsReadyResponse.OK =>
        Right(true)
      case externalHttp.IsReadyResponse.ServiceUnavailable =>
        Right(false)
    }
  }
}
