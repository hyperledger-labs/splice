// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.app_manager_public as http
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

object HttpAppManagerPublicAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.AppManagerPublicClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.AppManagerPublicClient.httpClient(
        HttpClientBuilder().buildClient(),
        host,
      )
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
      Right(response.configuration)
    }
  }

  final case class GetLatestAppConfigurationByName(name: String)
      extends BaseCommand[
        http.GetLatestAppConfigurationByNameResponse,
        definitions.AppConfiguration,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetLatestAppConfigurationByNameResponse] =
      client.getLatestAppConfigurationByName(name, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetLatestAppConfigurationByNameResponse.OK(response) =>
      Right(response.configuration)
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
