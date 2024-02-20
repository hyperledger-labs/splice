package com.daml.network.validator.migration

import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.identities.NodeIdentitiesStore
import com.daml.network.migration.{AcsExporter, DarExporter}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

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
      acsSnapshot <- acsExporter
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
    } yield {
      DomainMigrationDump(
        domainId = domain,
        migrationId = migrationId,
        participant = nodeIdentities,
        acsSnapshot = acsSnapshot,
        dars = dars,
      )
    }
  }

}
