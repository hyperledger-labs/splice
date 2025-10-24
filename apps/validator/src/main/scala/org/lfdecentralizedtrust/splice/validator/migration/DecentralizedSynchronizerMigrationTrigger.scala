// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.migration

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationTrigger
import org.lfdecentralizedtrust.splice.migration.DomainMigrationTrigger.ScheduledMigration
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

final class DecentralizedSynchronizerMigrationTrigger(
    override protected val currentMigrationId: Long,
    baseContext: TriggerContext,
    ledgerConnection: SpliceLedgerConnection,
    override protected val participantAdminConnection: ParticipantAdminConnection,
    override protected val dumpPath: Path,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends DomainMigrationTrigger[DomainMigrationDump]()(ec, mat, tracer, DomainMigrationDump.codec(Some(dumpPath.getParent.toString))) {

  // Disabling domain time and domain paused sync, as it runs after the domain is paused
  override protected lazy val context =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  override protected val sequencerAdminConnection: None.type = None

  private val dumpGenerator = new DomainMigrationDumpGenerator(
    ledgerConnection,
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

  override protected def getSynchronizerId()(implicit tc: TraceContext): Future[SynchronizerId] = {
    scanConnection.getAmuletRulesDomain()(tc)
  }

  override protected def existingDumpFileMigrationId(dump: DomainMigrationDump): Long =
    dump.migrationId

  override protected def existingDumpFileTimestamp(dump: DomainMigrationDump): Instant =
    dump.createdAt

  override protected def generateDump(
      task: DomainMigrationTrigger.Task
  )(implicit tc: TraceContext): Future[DomainMigrationDump] = {
    dumpGenerator
      .generateDomainDump(task.migrationId, task.synchronizerId)
  }

}
