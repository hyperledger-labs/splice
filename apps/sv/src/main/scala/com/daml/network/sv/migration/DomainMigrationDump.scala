// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.migration

import cats.implicits.toBifunctorOps
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.http.v0.definitions as http
import com.daml.network.sv.LocalSynchronizerNode
import com.daml.network.sv.migration.SynchronizerNodeIdentities.getSynchronizerNodeIdentities
import com.daml.network.sv.store.SvDsoStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import io.circe.{Codec, Decoder}
import io.circe.syntax.*

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class DomainMigrationDump(
    migrationId: Long,
    nodeIdentities: SynchronizerNodeIdentities,
    domainDataSnapshot: DomainDataSnapshot,
    createdAt: Instant,
) {
  def toHttp: http.GetDomainMigrationDumpResponse = http.GetDomainMigrationDumpResponse(
    migrationId,
    nodeIdentities.toHttp(),
    domainDataSnapshot.toHttp,
    createdAt.toString,
  )
}

object DomainMigrationDump {

  implicit val domainMigrationDumpCodec: Codec[DomainMigrationDump] = Codec.from(
    Decoder.decodeJson.emap(json =>
      json
        .as[http.GetDomainMigrationDumpResponse]
        .leftMap(err => s"Failed to decode: ${err.message}")
        .flatMap(fromHttp)
    ),
    (a: DomainMigrationDump) => a.toHttp.asJson,
  )

  def fromHttp(
      response: http.GetDomainMigrationDumpResponse
  ): Either[String, DomainMigrationDump] = for {
    identities <- SynchronizerNodeIdentities.fromHttp(response.identities)
    snapshot <- DomainDataSnapshot.fromHttp(response.dataSnapshot)
    timestamp <- Try(Instant.parse(response.createdAt)).toEither.leftMap(_.getMessage)
  } yield DomainMigrationDump(
    response.migrationId,
    nodeIdentities = identities,
    domainDataSnapshot = snapshot,
    createdAt = timestamp,
  )

  def getDomainMigrationDump(
      domainAlias: DomainAlias,
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerNode: LocalSynchronizerNode,
      loggerFactory: NamedLoggerFactory,
      dsoStore: SvDsoStore,
      migrationId: Long,
      domainDataSnapshotGenerator: DomainDataSnapshotGenerator,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainMigrationDump] = {
    for {
      identities <- getSynchronizerNodeIdentities(
        participantAdminConnection,
        synchronizerNode,
        dsoStore,
        domainAlias,
        loggerFactory,
      )
      snapshot <- domainDataSnapshotGenerator.getDomainMigrationSnapshot
    } yield DomainMigrationDump(
      migrationId,
      identities,
      snapshot,
      Instant.now(),
    )
  }

}
