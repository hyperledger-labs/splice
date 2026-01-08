// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api.client

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.environment.SpliceStatus
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.external.common_admin as externalHttp
import org.lfdecentralizedtrust.splice.http.v0.external.common_admin.CommonAdminClient
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.tracing.TraceContext

import java.time.OffsetDateTime
import scala.concurrent.{ExecutionContext, Future}

object PrefixedCommonAdminClient {
  def apply(host: String, basePath: String)(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      ec: ExecutionContext,
      mat: Materializer,
  ): PrefixedCommonAdminClient = new PrefixedCommonAdminClient(host, basePath)(httpClient, ec, mat)

  def httpClient(httpClient: HttpRequest => Future[HttpResponse], host: String, basePath: String)(
      implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): PrefixedCommonAdminClient = new PrefixedCommonAdminClient(host, basePath)(httpClient, ec, mat)
}
class PrefixedCommonAdminClient(host: String, override val basePath: String)(implicit
    httpClient: HttpRequest => Future[HttpResponse],
    ec: ExecutionContext,
    mat: Materializer,
) extends CommonAdminClient(host) {}

object HttpAdminAppClient {
  abstract class BaseCommand[Res, Result](basePath: String) extends HttpCommand[Res, Result] {
    override type Client = PrefixedCommonAdminClient

    override def createClient(host: String, clientName: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      PrefixedCommonAdminClient.httpClient(
        HttpClientBuilder().buildClient(clientName, commandName),
        host,
        basePath,
      )
  }

  case class GetHealthStatus[S <: NodeStatus.Status](
      basePath: String,
      deserialize: definitions.Status => Either[String, S],
  ) extends BaseCommand[externalHttp.GetHealthStatusResponse, NodeStatus[S]](basePath) {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.GetHealthStatusResponse] =
      client.getHealthStatus(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.GetHealthStatusResponse.OK(response) =>
      SpliceStatus.fromHttpNodeStatus(deserialize)(response)
    }
  }

  case class VersionInfo(version: String, commitTs: OffsetDateTime)

  case class GetVersion(basePath: String)
      extends BaseCommand[externalHttp.GetVersionResponse, VersionInfo](basePath) {

    override def createClient(host: String, clientName: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): PrefixedCommonAdminClient = PrefixedCommonAdminClient.httpClient(
      HttpClientBuilder()(
        httpClient.withOverrideParameters(
          // Getting the version of an app is done on startup.
          // If there's no response within 5s, we assume the app to be down.
          HttpClient.HttpRequestParameters(requestTimeout = NonNegativeDuration.ofSeconds(5))
        ),
        ec,
        mat,
      ).buildClient(clientName, commandName),
      host,
      basePath,
    )

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.GetVersionResponse] =
      client.getVersion(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case externalHttp.GetVersionResponse.OK(response) =>
        Right(VersionInfo(response.version, response.commitTs))
    }
  }

  case class IsLive(basePath: String)
      extends BaseCommand[externalHttp.IsLiveResponse, Boolean](basePath) {
    override def submitRequest(
        client: Client,
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
  case class IsReady(basePath: String)
      extends BaseCommand[externalHttp.IsReadyResponse, Boolean](basePath) {
    override def submitRequest(
        client: Client,
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
