package com.daml.network.validator.admin.api.client.commands

import akka.http.scaladsl.model.{BodyPartEntity, HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.http.v0.appManager as http
import com.daml.network.http.v0.definitions
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.circe.syntax.*

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

  final case class RegisterApp(
      providerUserId: String,
      configuration: definitions.AppConfiguration,
      release: BodyPartEntity,
  ) extends BaseCommand[http.RegisterAppResponse, Unit] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.RegisterAppResponse] =
      client.registerApp(
        providerUserId,
        configuration.asJson.noSpaces,
        release,
        headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.RegisterAppResponse.Created =>
      Right(())
    }
  }

  final case class PublishAppRelease(
      provider: PartyId,
      release: BodyPartEntity,
  ) extends BaseCommand[http.PublishAppReleaseResponse, Unit] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.PublishAppReleaseResponse] =
      client.publishAppRelease(
        provider.toProtoPrimitive,
        release,
        headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.PublishAppReleaseResponse.Created =>
      Right(())
    }
  }

  final case class UpdateAppConfiguration(
      provider: PartyId,
      configuration: definitions.AppConfiguration,
  ) extends BaseCommand[http.UpdateAppConfigurationResponse, Unit] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.UpdateAppConfigurationResponse] =
      client.updateAppConfiguration(
        provider.toProtoPrimitive,
        definitions.UpdateAppConfigurationRequest(
          configuration
        ),
        headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.UpdateAppConfigurationResponse.Created =>
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

  final case class GetLatestAppConfiguration(provider: PartyId)
      extends BaseCommand[http.GetLatestAppConfigurationResponse, definitions.AppConfiguration] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetLatestAppConfigurationResponse] =
      client.getLatestAppConfiguration(provider.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetLatestAppConfigurationResponse.OK(response) =>
      Right(response)
    }
  }

  final case class GetAppRelease(provider: PartyId, version: String)
      extends BaseCommand[http.GetAppReleaseResponse, definitions.AppRelease] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAppReleaseResponse] =
      client.getAppRelease(provider.toProtoPrimitive, version, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetAppReleaseResponse.OK(response) =>
      Right(response)
    }
  }

  final case class GetDarFile(darHash: String)
      extends BaseCommand[http.GetDarFileResponse, ByteString] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetDarFileResponse] =
      client.getDarFile(darHash, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDarFileResponse.OK(response) =>
      Right(ByteString.copyFrom(Base64.getDecoder().decode(response.base64Dar)))
    }
  }

  final case class InstallApp(appUrl: String) extends BaseCommand[http.InstallAppResponse, Unit] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.InstallAppResponse] =
      client.installApp(definitions.InstallAppRequest(appUrl), headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.InstallAppResponse.Created =>
      Right(())
    }
  }

  final case object ListInstalledApps
      extends BaseCommand[http.ListInstalledAppsResponse, Seq[definitions.InstalledApp]] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListInstalledAppsResponse] =
      client.listInstalledApps(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListInstalledAppsResponse.OK(response) =>
      Right(response.apps)
    }
  }

  final case class AuthorizeApp(
      provider: PartyId,
      userId: String,
  ) extends BaseCommand[http.AuthorizeAppResponse, Unit] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.AuthorizeAppResponse] =
      client.authorizeApp(provider.toProtoPrimitive, userId, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.AuthorizeAppResponse.OK =>
      Right(())
    }
  }

  final case class CheckAppAuthorized(
      provider: PartyId,
      redirectUri: String,
      state: String,
      userId: String,
  ) extends BaseCommand[http.CheckAppAuthorizedResponse, String] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.CheckAppAuthorizedResponse] =
      client.checkAppAuthorized(provider.toProtoPrimitive, redirectUri, state, userId, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.CheckAppAuthorizedResponse.OK(response) =>
      Right(response.redirectUri)
    }
  }

  final case object Oauth2Jwks
      extends BaseCommand[http.Oauth2JwksResponse, definitions.JwksResponse] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.Oauth2JwksResponse] =
      client.oauth2Jwks(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.Oauth2JwksResponse.OK(response) =>
      Right(response)
    }
  }

  final case object Oauth2OpenIdConfiguration
      extends BaseCommand[
        http.Oauth2OpenIdConfigurationResponse,
        definitions.OpenIdConfigurationResponse,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.Oauth2OpenIdConfigurationResponse] =
      client.oauth2OpenIdConfiguration(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.Oauth2OpenIdConfigurationResponse.OK(response) =>
      Right(response)
    }
  }

  final case class Oauth2Token(
      grantType: String,
      code: String,
      redirectUri: String,
      clientId: String,
  ) extends BaseCommand[http.Oauth2TokenResponse, definitions.TokenResponse] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.Oauth2TokenResponse] =
      client.oauth2Token(grantType, code, redirectUri, clientId, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.Oauth2TokenResponse.OK(response) =>
      Right(response)
    }
  }
}
