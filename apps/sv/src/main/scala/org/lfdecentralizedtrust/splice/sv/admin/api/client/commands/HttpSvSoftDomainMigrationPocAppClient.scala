// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import com.digitalasset.canton.config.NonNegativeDuration
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.sv_soft_domain_migration_poc as http
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

object HttpSvSoftDomainMigrationPocAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvSoftDomainMigrationPocClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.SvSoftDomainMigrationPocClient.httpClient(
        HttpClientBuilder()(
          httpClient.withOverrideParameters(
            HttpClient.HttpRequestParameters(requestTimeout = NonNegativeDuration.ofMinutes(1))
          ),
          ec,
          mat,
        ).buildClient(),
        host,
      )
  }

  case class InitializeSynchronizer(domainIdPrefix: String)
      extends BaseCommand[http.InitializeSynchronizerResponse, Unit] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.InitializeSynchronizerResponse] =
      client.initializeSynchronizer(domainIdPrefix, headers = headers)
    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.InitializeSynchronizerResponse.OK => Right(())
    }
  }

  case class ReconcileSynchronizerDamlState(domainIdPrefix: String)
      extends BaseCommand[http.ReconcileSynchronizerDamlStateResponse, Unit] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ReconcileSynchronizerDamlStateResponse] =
      client.reconcileSynchronizerDamlState(domainIdPrefix, headers = headers)
    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ReconcileSynchronizerDamlStateResponse.OK => Right(())
    }
  }

  case class SignDsoPartyToParticipant(domainIdPrefix: String)
      extends BaseCommand[http.SignDsoPartyToParticipantResponse, Unit] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.SignDsoPartyToParticipantResponse] =
      client.signDsoPartyToParticipant(domainIdPrefix, headers = headers)
    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.SignDsoPartyToParticipantResponse.OK => Right(())
    }
  }
}
