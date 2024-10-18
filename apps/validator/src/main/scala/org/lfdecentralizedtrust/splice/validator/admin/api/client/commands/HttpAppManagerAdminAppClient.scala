// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{BodyPartEntity, HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.app_manager_admin as http
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.syntax.*

import scala.concurrent.{ExecutionContext, Future}

object HttpAppManagerAdminAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.AppManagerAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.AppManagerAdminClient.httpClient(
        HttpClientBuilder().buildClient(),
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

  final case class ApproveAppReleaseConfiguration(
      provider: PartyId,
      configurationVersion: Long,
      releaseConfigurationIndex: Int,
  ) extends BaseCommand[http.ApproveAppReleaseConfigurationResponse, Unit] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ApproveAppReleaseConfigurationResponse] =
      client.approveAppReleaseConfiguration(
        provider.toProtoPrimitive,
        definitions
          .ApproveAppReleaseConfigurationRequest(configurationVersion, releaseConfigurationIndex),
        headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ApproveAppReleaseConfigurationResponse.OK =>
      Right(())
    }
  }
}
