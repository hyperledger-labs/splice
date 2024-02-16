package com.daml.network.sv.migration

import cats.implicits.toBifunctorOps
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.http.v0.definitions as http
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.migration.DomainNodeIdentities.getDomainNodeIdentities
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import io.circe.{Codec, Decoder}
import io.circe.syntax.*

import scala.concurrent.{ExecutionContext, Future}

case class DomainMigrationDump(
    migrationId: Long,
    nodeIdentities: DomainNodeIdentities,
    domainDataSnapshot: DomainDataSnapshot,
) {
  def toHttp: http.GetDomainMigrationDumpResponse = http.GetDomainMigrationDumpResponse(
    migrationId,
    nodeIdentities.toHttp(),
    domainDataSnapshot.toHttp,
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
    identities <- DomainNodeIdentities.fromHttp(response.identities)
    snapshot <- DomainDataSnapshot.fromHttp(response.dataSnapshot)
  } yield DomainMigrationDump(
    response.migrationId,
    nodeIdentities = identities,
    domainDataSnapshot = snapshot,
  )

  def getDomainMigrationDump(
      domainAlias: DomainAlias,
      participantAdminConnection: ParticipantAdminConnection,
      domainNode: LocalDomainNode,
      loggerFactory: NamedLoggerFactory,
      svcStore: SvSvcStore,
      migrationId: Long,
      domainDataSnapshotGenerator: DomainDataSnapshotGenerator,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainMigrationDump] = {
    for {
      identities <- getDomainNodeIdentities(
        participantAdminConnection,
        domainNode,
        svcStore,
        domainAlias,
        loggerFactory,
      )
      snapshot <- domainDataSnapshotGenerator.getDomainMigrationSnapshot()
    } yield DomainMigrationDump(
      migrationId,
      identities,
      snapshot,
    )
  }

}
