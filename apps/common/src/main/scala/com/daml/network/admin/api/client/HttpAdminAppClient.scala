package com.daml.network.admin.api.client

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.http.v0.{definitions, commonAdmin as http}

import scala.concurrent.{ExecutionContext, Future}
import cats.data.EitherT
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.health.admin.data.NodeStatus

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
}
