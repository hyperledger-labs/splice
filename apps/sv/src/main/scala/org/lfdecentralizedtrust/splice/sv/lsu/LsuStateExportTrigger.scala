// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.transaction.LsuAnnouncement
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.syntax.EncoderOps
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
  TriggerEnabledSynchronization,
}
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.sv.lsu.LsuStateExportTrigger.LsuExportTask
import org.lfdecentralizedtrust.splice.util.BackupDump

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class LsuStateExportTrigger(
    baseContext: TriggerContext,
    sequencerAdminConnection: SequencerAdminConnection,
    mediatorAdminConnection: MediatorAdminConnection,
    exportPath: Path,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[LsuExportTask] {

  private val exporter =
    new LsuStateExporter(sequencerAdminConnection, mediatorAdminConnection, loggerFactory)

  override protected lazy val context: TriggerContext =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[LsuExportTask]] = {
    for {
      physicalSynchronizerId <- sequencerAdminConnection.getPhysicalSynchronizerId()
      announcements <- announcements(physicalSynchronizerId)
    } yield {
      announcements
        .map(result => LsuExportTask(result.mapping))
    }

  }

  protected def completeTask(task: ScheduledTaskTrigger.ReadyTask[LsuExportTask])(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    if (
      BackupDump.fileExists(exportPath) && readExistingExport().exists(
        _.upgradesAt == task.work.announcement.upgradeTime.toInstant
      )
    ) {
      logger.info(s"Export already exists for upgrade $task. Will not generate a new one.")
      Future.successful(TaskNoop)
    } else {
      logger.info(s"Generating export for $task.")
      exporter
        .exportLSUState(task.work.announcement.upgradeTime)
        .map(state => {
          BackupDump.writeToPath(exportPath, state.asJson.noSpaces).discard
          TaskSuccess(s"Generated export at path $exportPath for $task")
        })
    }
  }

  protected def isStaleTask(task: ScheduledTaskTrigger.ReadyTask[LsuExportTask])(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    for {
      physicalSynchronizerId <- sequencerAdminConnection.getPhysicalSynchronizerId()
      announcements <- announcements(physicalSynchronizerId)
    } yield {
      !announcements.map(_.mapping).contains(task.work.announcement)
    }
  }

  private def announcements(synchronizerId: PhysicalSynchronizerId)(implicit tc: TraceContext) = {
    sequencerAdminConnection
      .listLsuAnnouncements(synchronizerId.logical)
      .map(_.filter { announcement =>
        announcement.base.validFrom
          .isAfter(Instant.now()) && announcement.mapping.successorSynchronizerId != synchronizerId
      })
  }

  private def readExistingExport()(implicit tc: TraceContext) = {
    BackupDump
      .readFromPath[LsuState](exportPath)
      .fold(
        err => {
          logger.error(s"Failed to read lsu export from path $exportPath", err)
          None
        },
        dump => Some(dump),
      )
  }
}

object LsuStateExportTrigger {
  case class LsuExportTask(announcement: LsuAnnouncement) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = prettyOfClass()
  }
}
