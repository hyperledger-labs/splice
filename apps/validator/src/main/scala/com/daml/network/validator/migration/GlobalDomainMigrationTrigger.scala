package com.daml.network.validator.migration

import cats.data.OptionT
import com.daml.network.automation.TriggerContext
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.migration.DomainMigrationTrigger
import com.daml.network.migration.DomainMigrationTrigger.ScheduledMigration
import com.daml.network.scan.admin.api.client.ScanConnection
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

final class GlobalDomainMigrationTrigger(
    override protected val currentMigrationId: Long,
    override protected val context: TriggerContext,
    override protected val participantAdminConnection: ParticipantAdminConnection,
    override protected val dumpPath: Path,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends DomainMigrationTrigger[DomainMigrationDump] {

  private val dumpGenerator = new DomainMigrationDumpGenerator(
    participantAdminConnection,
    context.retryProvider,
    context.loggerFactory,
  )

  override protected def getSchedule(implicit
      tc: TraceContext
  ): OptionT[Future, DomainMigrationTrigger.ScheduledMigration] =
    scanConnection
      .getMigrationSchedule()
      .map(schedule => ScheduledMigration(schedule.time.toInstant, schedule.migrationId))

  override protected def getDomainId()(implicit tc: TraceContext): Future[DomainId] = {
    scanConnection.getCoinRulesDomain()(tc)
  }

  override protected def existingDumpFileMigrationId(dump: DomainMigrationDump): Long =
    dump.migrationId

  override protected def generateDump(
      task: DomainMigrationTrigger.Task
  )(implicit tc: TraceContext): Future[DomainMigrationDump] = {
    dumpGenerator
      .generateDomainDump(task.migrationId, task.domainId)
  }

}
