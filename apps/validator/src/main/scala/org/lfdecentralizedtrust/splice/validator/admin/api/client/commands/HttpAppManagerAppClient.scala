// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.app_manager as http
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

object HttpAppManagerAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.AppManagerClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.AppManagerClient.httpClient(
        HttpClientBuilder().buildClient(),
        host,
      )
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
      provider: PartyId
  ) extends BaseCommand[http.AuthorizeAppResponse, Unit] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.AuthorizeAppResponse] =
      client.authorizeApp(provider.toProtoPrimitive, headers)

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
  ) extends BaseCommand[http.CheckAppAuthorizedResponse, String] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.CheckAppAuthorizedResponse] =
      client.checkAppAuthorized(provider.toProtoPrimitive, redirectUri, state, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.CheckAppAuthorizedResponse.OK(response) =>
      Right(response.redirectUri)
    }
  }
}
