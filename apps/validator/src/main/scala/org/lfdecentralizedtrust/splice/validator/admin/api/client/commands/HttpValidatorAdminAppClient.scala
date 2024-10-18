// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.validator_admin as http
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import org.lfdecentralizedtrust.splice.validator.migration.DomainMigrationDump

object HttpValidatorAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ValidatorAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ValidatorAdminClient.httpClient(
        HttpClientBuilder().buildClient(),
        host,
      )
  }

  case class OnboardUser(name: String, existingPartyId: Option[PartyId])
      extends BaseCommand[http.OnboardUserResponse, PartyId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardUserResponse] =
      client.onboardUser(
        definitions.OnboardUserRequest(name, existingPartyId.map(_.toProtoPrimitive)),
        headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.OnboardUserResponse.OK(response) =>
      Codec.decode(Codec.Party)(response.partyId)
    }
  }

  case object ListUsers extends BaseCommand[http.ListUsersResponse, Seq[String]] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListUsersResponse] =
      client.listUsers(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListUsersResponse.OK(response) => Right(response.usernames.toSeq)
    }
  }

  case class OffboardUser(username: String) extends BaseCommand[http.OffboardUserResponse, Unit] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OffboardUserResponse] =
      client.offboardUser(username, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.OffboardUserResponse.OK => Right(())
    }
  }

  case class DumpParticipantIdentities()
      extends BaseCommand[
        http.DumpParticipantIdentitiesResponse,
        NodeIdentitiesDump,
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.DumpParticipantIdentitiesResponse] =
      client.dumpParticipantIdentities(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.DumpParticipantIdentitiesResponse.OK(response) =>
        NodeIdentitiesDump.fromHttp(ParticipantId.tryFromProtoPrimitive, response)
    }
  }

  case class GetValidatorDomainDataSnapshot(
      timestamp: Instant,
      migrationId: Option[Long],
      force: Boolean,
  ) extends BaseCommand[
        http.GetValidatorDomainDataSnapshotResponse,
        DomainMigrationDump,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetValidatorDomainDataSnapshotResponse] =
      client.getValidatorDomainDataSnapshot(
        timestamp.toString,
        migrationId = migrationId,
        force = Some(force),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetValidatorDomainDataSnapshotResponse.OK(response) =>
      DomainMigrationDump.fromHttp(response.dataSnapshot)
    }
  }

  case class GetDecentralizedSynchronizerConnectionConfig()
      extends BaseCommand[
        http.GetDecentralizedSynchronizerConnectionConfigResponse,
        definitions.GetDecentralizedSynchronizerConnectionConfigResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetDecentralizedSynchronizerConnectionConfigResponse] =
      client.getDecentralizedSynchronizerConnectionConfig(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      http.GetDecentralizedSynchronizerConnectionConfigResponse,
      Either[String, definitions.GetDecentralizedSynchronizerConnectionConfigResponse],
    ] = { case http.GetDecentralizedSynchronizerConnectionConfigResponse.OK(response) =>
      Right(response)
    }
  }

}
