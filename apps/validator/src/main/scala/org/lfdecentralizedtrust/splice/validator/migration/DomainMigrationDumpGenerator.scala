// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.migration

import cats.implicits.catsSyntaxOptionId
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SpliceLedgerConnection,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesStore
import org.lfdecentralizedtrust.splice.migration.{
  AcsExporter,
  DarExporter,
  ParticipantUsersDataExporter,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class DomainMigrationDumpGenerator(
    ledgerConnection: SpliceLedgerConnection,
    participantConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val nodeIdentityStore =
    new NodeIdentitiesStore(participantConnection, None, loggerFactory)
  private val acsExporter = new AcsExporter(participantConnection, retryProvider, loggerFactory)
  private val darExporter = new DarExporter(participantConnection)
  private val participantUsersDataExporter = new ParticipantUsersDataExporter(ledgerConnection)

  // This is the safe option used for migrations
  def generateDomainDump(
      migrationId: Long,
      domain: SynchronizerId,
  )(implicit tc: TraceContext): Future[DomainMigrationDump] = {
    for {
      (acsSnapshot, acsTimestamp) <- acsExporter
        .safeExportParticipantPartiesAcsFromPausedDomain(domain)
        .leftMap(failure =>
          Status.FAILED_PRECONDITION
            .withDescription("Failed to export ACS snapshot")
            .augmentDescription(failure.toString)
            .asRuntimeException()
        )
        .rethrowT
      nodeIdentities <- nodeIdentityStore.getNodeIdentitiesDump()
      participantUsersData <- participantUsersDataExporter.exportParticipantUsersData()
      dars <- darExporter.exportAllDars()
      createdAt = Instant.now()
    } yield {
      val result = DomainMigrationDump(
        domainId = domain,
        migrationId = migrationId,
        participant = nodeIdentities,
        participantUsers = participantUsersData,
        // FIXME
        acsSnapshot = com.google.protobuf.ByteString.copyFrom(acsSnapshot.asJava),
        acsTimestamp = acsTimestamp,
        dars = dars,
        createdAt = createdAt,
        synchronizerWasPaused = true,
      )
      logger.info(
        show"Finished generating $result"
      )
      result
    }
  }

  // This is the safe option used for DR
  def getDomainDataSnapshot(
      timestamp: Instant,
      domain: SynchronizerId,
      migrationId: Long,
      force: Boolean,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainMigrationDump] = {
    for {
      participantId <- participantConnection.getId()
      parties <- participantConnection
        .listPartyToParticipant(
          store = TopologyStoreId.SynchronizerStore(domain).some,
          filterParticipant = participantId.toProtoPrimitive,
        )
        .map(_.map(_.mapping.partyId))
      nodeIdentities <- nodeIdentityStore.getNodeIdentitiesDump()
      participantUsersData <- participantUsersDataExporter.exportParticipantUsersData()
      acsSnapshot <- acsExporter.exportAcsAtTimestamp(
        domain,
        timestamp,
        force,
        parties*
      )
      dars <- darExporter.exportAllDars()
    } yield {
      DomainMigrationDump(
        domainId = domain,
        migrationId = migrationId,
        participant = nodeIdentities,
        participantUsers = participantUsersData,
        // FIXME
        acsSnapshot = com.google.protobuf.ByteString.copyFrom(acsSnapshot.asJava),
        acsTimestamp = timestamp,
        dars = dars,
        createdAt = Instant.now(),
        synchronizerWasPaused = false,
      )
    }
  }

}
