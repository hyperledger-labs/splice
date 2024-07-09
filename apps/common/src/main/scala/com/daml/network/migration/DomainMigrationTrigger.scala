// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.migration

import cats.data.OptionT
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess}
import com.daml.network.environment.{ParticipantAdminConnection, SequencerAdminConnection}
import com.daml.network.migration
import com.daml.network.util.BackupDump
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.circe.Codec
import io.circe.syntax.EncoderOps
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

abstract class DomainMigrationTrigger[T: Codec](implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[DomainMigrationTrigger.Task] {
  protected val participantAdminConnection: ParticipantAdminConnection
  protected val sequencerAdminConnection: Option[SequencerAdminConnection]
  protected val dumpPath: Path
  protected val currentMigrationId: Long

  protected def getSchedule(implicit
      tc: TraceContext
  ): OptionT[Future, DomainMigrationTrigger.ScheduledMigration]

  protected def getDomainId()(implicit tc: TraceContext): Future[DomainId]

  protected def existingDumpFileMigrationId(dump: T): Long

  protected def existingDumpFileTimestamp(dump: T): Instant

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[migration.DomainMigrationTrigger.Task]] = {
    (for {
      schedule <- getSchedule
      domainId <- OptionT.liftF(getDomainId()(tc))
      domainTimeLowerBound <- OptionT.liftF(
        participantAdminConnection
          .getDomainTimeLowerBound(domainId, maxDomainTimeLag = context.config.pollingInterval)
      )
      domainTimeIsAfterTheScheduledTime = domainTimeLowerBound.timestamp.toInstant.isAfter(
        schedule.time
      )
      task <-
        if (
          domainTimeIsAfterTheScheduledTime && currentMigrationId + 1 == schedule.migrationId && !expectedDumpExists(
            schedule
          )
        )
          OptionT.pure[Future](DomainMigrationTrigger.Task(domainId, schedule.migrationId))
        else OptionT.none[Future, DomainMigrationTrigger.Task]
    } yield task).value.map(_.toList)
  }

  private def expectedDumpExists(
      schedule: DomainMigrationTrigger.ScheduledMigration
  )(implicit tc: TraceContext) = {
    BackupDump.fileExists(dumpPath) && readExistingDump()
      .map(dump =>
        existingDumpFileMigrationId(dump) == schedule.migrationId && existingDumpFileTimestamp(dump)
          .isAfter(schedule.time)
      )
      .getOrElse(false)
  }

  private def readExistingDump()(implicit tc: TraceContext) = {
    BackupDump
      .readFromPath[T](dumpPath)
      .fold(
        err => {
          logger.error(s"Failed to read domain migration dump from path $dumpPath", err)
          None
        },
        dump => Some(dump),
      )
  }

  override protected def completeTask(task: migration.DomainMigrationTrigger.ReadyTask)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      _ <- exportMigrationDump(task.work)
    } yield TaskSuccess(show"Triggered migration dump export for ${task.work}")
  }

  override protected def isStaleTask(
      task: migration.DomainMigrationTrigger.ReadyTask
  )(implicit tc: TraceContext): Future[Boolean] = Future.successful(false)

  protected def generateDump(task: DomainMigrationTrigger.Task)(implicit
      tc: TraceContext
  ): Future[T]

  private def exportMigrationDump(task: DomainMigrationTrigger.Task)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    generateDump(task)
      .map { dump =>
        val path = BackupDump.writeToPath(
          dumpPath,
          dump.asJson.noSpaces,
        )
        logger.info(s"Wrote domain migration dump at path $path")
      }
  }

}

object DomainMigrationTrigger {

  case class ScheduledMigration(time: Instant, migrationId: Long)

  case class Task(domainId: DomainId, migrationId: Long) extends PrettyPrinting {

    import com.daml.network.util.PrettyInstances.*

    override def pretty: Pretty[this.type] =
      prettyOfClass(param("migrationId", _.migrationId))
  }

  private type ReadyTask = ScheduledTaskTrigger.ReadyTask[Task]

}
