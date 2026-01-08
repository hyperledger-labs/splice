// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.TriggerDomainMigrationDumpRequest
import org.lfdecentralizedtrust.splice.http.v0.sv_admin.TriggerDomainMigrationDumpResponse
import org.lfdecentralizedtrust.splice.http.v0.sv_admin as http
import org.lfdecentralizedtrust.splice.sv.migration.{
  DomainDataSnapshot,
  DomainMigrationDump,
  SynchronizerNodeIdentities,
}
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

object HttpSvAdminAppClient {
  val clientName = "HttpSvAdminAppClient"
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvAdminClient

    def createClient(host: String, clientName: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.SvAdminClient.httpClient(
        HttpClientBuilder().buildClient(clientName, commandName),
        host,
      )
  }

  case class PauseDecentralizedSynchronizer()
      extends BaseCommand[http.PauseDecentralizedSynchronizerResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.PauseDecentralizedSynchronizerResponse] =
      client.pauseDecentralizedSynchronizer(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.PauseDecentralizedSynchronizerResponse.OK =>
      Right(())
    }
  }

  case class UnpauseDecentralizedSynchronizer()
      extends BaseCommand[http.UnpauseDecentralizedSynchronizerResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.UnpauseDecentralizedSynchronizerResponse] =
      client.unpauseDecentralizedSynchronizer(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.UnpauseDecentralizedSynchronizerResponse.OK =>
      Right(())
    }
  }

  case class TriggerDomainMigrationDump(migrationId: Long, at: Option[Instant])
      extends BaseCommand[
        http.TriggerDomainMigrationDumpResponse,
        Unit,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], TriggerDomainMigrationDumpResponse] =
      client.triggerDomainMigrationDump(
        headers = headers,
        body = TriggerDomainMigrationDumpRequest(migrationId, at.map(_.toString)),
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.TriggerDomainMigrationDumpResponse.OK =>
      Right(())
    }
  }
  case class GetDomainMigrationDump()
      extends BaseCommand[
        http.GetDomainMigrationDumpResponse,
        DomainMigrationDump,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetDomainMigrationDumpResponse] =
      client.getDomainMigrationDump(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDomainMigrationDumpResponse.OK(response) =>
      DomainMigrationDump.fromHttp(response)
    }
  }

  case class GetDomainDataSnapshot(
      timestamp: Instant,
      partyId: Option[PartyId],
      migrationId: Option[Long],
      force: Boolean,
  ) extends BaseCommand[
        http.GetDomainDataSnapshotResponse,
        DomainDataSnapshot.Response,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetDomainDataSnapshotResponse] =
      client.getDomainDataSnapshot(
        timestamp.toString,
        partyId.map(_.toProtoPrimitive),
        migrationId = migrationId,
        force = Some(force),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDomainDataSnapshotResponse.OK(response) =>
      DomainDataSnapshot.Response.fromHttp(response)
    }

  }

  case class GetSynchronizerNodeIdentitiesDump()
      extends BaseCommand[
        http.GetSynchronizerNodeIdentitiesDumpResponse,
        SynchronizerNodeIdentities,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetSynchronizerNodeIdentitiesDumpResponse] =
      client.getSynchronizerNodeIdentitiesDump(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetSynchronizerNodeIdentitiesDumpResponse.OK(response) =>
      SynchronizerNodeIdentities.fromHttp(response.identities)
    }
  }
}
