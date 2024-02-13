package com.daml.network.sv.migration

import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.http.v0.definitions as http
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.migration.DomainNodeIdentities.getDomainNodeIdentities
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import io.circe.syntax.*

import java.time.Instant
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

  def toJson: Json = {
    toHttp.asJson
  }
}

object DomainMigrationDump {
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
      domainPausedTime: Instant,
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
      snapshot <- DomainDataSnapshot.getDomainDataSnapshot(
        participantAdminConnection,
        svcStore,
        domainPausedTime,
      )
    } yield DomainMigrationDump(
      migrationId,
      identities,
      snapshot,
    )
  }

  def getDomainPausedTime(
      participantAdminConnection: ParticipantAdminConnection,
      svcStore: SvSvcStore,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[Instant]] = for {
    globalDomain <- svcStore.getSvcRules().map(_.domain)
    domainParamsTopologyResult <- participantAdminConnection.getDomainParametersState(
      globalDomain
    )
    isDomainPaused =
      domainParamsTopologyResult.mapping.parameters.maxRatePerParticipant == NonNegativeInt.zero
  } yield
    if (isDomainPaused) Some(domainParamsTopologyResult.base.validFrom)
    else None

}
