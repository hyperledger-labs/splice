package com.daml.network.migration

import cats.data.OptionT
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess}
import com.daml.network.environment.ParticipantAdminConnection
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

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

abstract class DomainMigrationTrigger[T: Codec](implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[DomainMigrationTrigger.Task] {
  protected val participantAdminConnection: ParticipantAdminConnection
  protected val dumpPath: Path
  protected val currentMigrationId: Long

  protected def getSchedule(implicit
      tc: TraceContext
  ): OptionT[Future, DomainMigrationTrigger.ScheduledMigration]

  protected def getDomainId()(implicit tc: TraceContext): Future[DomainId]

  protected def existingDumpFileMigrationId(dump: T): Long

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[migration.DomainMigrationTrigger.Task]] = {
    (for {
      schedule <- getSchedule
      domainId <- OptionT.liftF(getDomainId()(tc))
      domainTime <- OptionT.liftF(
        participantAdminConnection.getDomainTime(domainId, timeouts.default)
      )
      domainTimeIsAfterTheScheduledTime = domainTime.timestamp.toInstant.isAfter(
        schedule.time
      )
      task <-
        // check if the domain time is after the scheduled time for migration
        // and the dump does not exist or the dump exists and the migrationId is different compared to the scheduled one
        if (
          domainTimeIsAfterTheScheduledTime
          && currentMigrationId + 1 == schedule.migrationId && (!BackupDump
            .fileExists(dumpPath) || !readMigrationIdFromExistingDump().contains(
            schedule.migrationId
          ))
        )
          OptionT.pure[Future](DomainMigrationTrigger.Task(domainId, schedule.migrationId))
        else OptionT.none[Future, DomainMigrationTrigger.Task]
    } yield task).value.map(_.toList)
  }

  private def readMigrationIdFromExistingDump()(implicit tc: TraceContext) = {
    BackupDump
      .readFromPath[T](dumpPath)
      .fold(
        err => {
          logger.error(s"Failed to read domain migration dump from path $dumpPath", err)
          None
        },
        dump => Some(existingDumpFileMigrationId(dump)),
      )
  }

  override protected def completeTask(task: migration.DomainMigrationTrigger.ReadyTask)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    if (!BackupDump.fileExists(dumpPath)) {
      for {
        _ <- exportMigrationDump(task.work)
      } yield TaskSuccess(show"Triggered migration dump export for ${task.work}")
    } else
      Future.successful(TaskSuccess(show"migration dump already exists. skipping ${task.work}"))
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
