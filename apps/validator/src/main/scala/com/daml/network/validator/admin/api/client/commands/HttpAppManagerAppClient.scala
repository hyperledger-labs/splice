package com.daml.network.validator.admin.api.client.commands

import akka.http.scaladsl.model.{BodyPartEntity, HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.google.protobuf.ByteString
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.http.v0.appManager as http
import com.daml.network.http.v0.definitions
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.tracing.TraceContext

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

object HttpAppManagerAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.AppManagerClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.AppManagerClient.httpClient(
        HttpClientBuilder().buildClient,
        host,
      )
  }

  final case class RegisterApp(appBundle: BodyPartEntity)
      extends BaseCommand[http.RegisterAppResponse, Unit] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.RegisterAppResponse] =
      client.registerApp(appBundle, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.RegisterAppResponse.Created =>
      Right(())
    }
  }

  final case object ListRegisteredApps
      extends BaseCommand[http.ListRegisteredAppsResponse, Seq[definitions.RegisteredApp]] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListRegisteredAppsResponse] =
      client.listRegisteredApps(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListRegisteredAppsResponse.OK(response) =>
      Right(response.apps)
    }
  }

  final case class GetAppManifest(app: String)
      extends BaseCommand[http.GetAppManifestResponse, definitions.Manifest] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAppManifestResponse] =
      client.getAppManifest(app, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetAppManifestResponse.OK(response) =>
      Right(response)
    }
  }

  final case class GetAppBundle(app: String)
      extends BaseCommand[http.GetAppBundleResponse, ByteString] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAppBundleResponse] =
      client.getAppBundle(app, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetAppBundleResponse.OK(response) =>
      Right(ByteString.copyFrom(Base64.getDecoder().decode(response.base64Bundle)))
    }
  }

}
