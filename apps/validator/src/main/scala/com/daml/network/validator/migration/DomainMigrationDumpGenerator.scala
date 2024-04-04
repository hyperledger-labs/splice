package com.daml.network.validator.migration

import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.identities.NodeIdentitiesStore
import com.daml.network.migration.{AcsExporter, DarExporter}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class DomainMigrationDumpGenerator(
    participantConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val nodeIdentityStore =
    new NodeIdentitiesStore(participantConnection, None, loggerFactory)
  private val acsExporter = new AcsExporter(participantConnection, retryProvider, loggerFactory)
  private val darExporter = new DarExporter(participantConnection)

  def generateDomainDump(
      migrationId: Long,
      domain: DomainId,
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
      dars <- darExporter.exportAllDars()
      createdAt = Instant.now()
    } yield {
      val result = DomainMigrationDump(
        domainId = domain,
        migrationId = migrationId,
        participant = nodeIdentities,
        acsSnapshot = acsSnapshot,
        acsTimestamp = acsTimestamp,
        dars = dars,
        createdAt = createdAt,
      )
      logger.info(
        show"Finished generating $result"
      )
      result
    }
  }

}
