package com.daml.network.admin.api.client

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.http.v0.{commonAdmin as http, definitions}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.health.admin.data.NodeStatus

import java.time.OffsetDateTime
import scala.concurrent.{ExecutionContext, Future}

object HttpAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.CommonAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.CommonAdminClient(host)
  }

  case class GetHealthStatus[S <: NodeStatus.Status](
      deserialize: definitions.Status => Either[String, S]
  ) extends BaseCommand[http.GetHealthStatusResponse, NodeStatus[S]] {
    override def submitRequest(
        client: http.CommonAdminClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetHealthStatusResponse] =
      client.getHealthStatus(headers)

    override def handleResponse(response: http.GetHealthStatusResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, NodeStatus[S]] = response match {
      case http.GetHealthStatusResponse.OK(response) => {
        response match {
          case definitions.NodeStatus(None, Some(success)) => {
            deserialize(success).map(NodeStatus.Success(_))
          }
          case definitions.NodeStatus(Some(notInitialized), None) => {
            Right(NodeStatus.NotInitialized(notInitialized.active))
          }
          case _ => Left("Unsuccessful status response")
        }
      }
    }
  }

  case class VersionInfo(version: String, commitTs: OffsetDateTime)

  case class GetVersion() extends BaseCommand[http.GetVersionResponse, VersionInfo] {
    override def submitRequest(
        client: http.CommonAdminClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetVersionResponse] =
      client.getVersion(headers)

    override def handleResponse(
        response: http.GetVersionResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, VersionInfo] =
      response match {
        case http.GetVersionResponse.OK(response) =>
          Right(VersionInfo(response.version, response.commitTs))
      }
  }
}
