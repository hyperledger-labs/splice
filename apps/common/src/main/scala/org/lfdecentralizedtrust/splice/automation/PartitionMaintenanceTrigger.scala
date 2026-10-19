// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.UpdateHistory

import java.time.{LocalDate, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

class PartitionMaintenanceTrigger(
    updateHistory: UpdateHistory,
    runwayDays: Int,
    override protected val context: TriggerContext,
)(implicit
    ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[PartitionMaintenanceTrigger.Task] {
  import PartitionMaintenanceTrigger.*

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] =
    if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else {
      missingDays(updateHistory, runwayDays, context.clock.now).map { case (_, missing) =>
        missing.map(Task(_))
      }
    }

  override protected def completeTask(task: Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      _ <- updateHistory.createPartitions(task.day)
      _ <- updateHistory.analyzePartitionedTables()
    } yield TaskSuccess(s"Created update history partitions for ${task.day}")

  override protected def isStaleTask(task: Task)(implicit tc: TraceContext): Future[Boolean] =
    updateHistory.listDatesWithAllPartitions().map(_.contains(task.day))
}

object PartitionMaintenanceTrigger {

  final case class Task(day: LocalDate) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(param("day", _.day.toString.unquoted))
  }

  def createMissingPartitions(
      updateHistory: UpdateHistory,
      runwayDays: Int,
      now: CantonTimestamp,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Seq[LocalDate]] =
    for {
      (_, missing) <- missingDays(updateHistory, runwayDays, now)
      _ <- MonadUtil.sequentialTraverse(missing)(day => updateHistory.createPartitions(day))
      _ <-
        if (missing.isEmpty) Future.unit
        else {
          logger.info(s"Created update history partitions for ${missing.mkString(", ")}")
          updateHistory.analyzePartitionedTables()
        }
    } yield missing

  private def missingDays(
      updateHistory: UpdateHistory,
      runwayDays: Int,
      now: CantonTimestamp,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[(Set[LocalDate], Seq[LocalDate])] =
    for {
      lastRecordTimeIngested <- updateHistory.readLastIngestedRecordTime()
      existing <- updateHistory.listDatesWithAllPartitions()
    } yield {
      // We need to have partitions _at least_ from the last update history entry,
      // otherwise we could have missing partitions when Scan is down for several days.
      val firstDay = dayOf(lastRecordTimeIngested.getOrElse(now))
      val lastDay = dayOf(now.plus(java.time.Duration.ofDays(runwayDays.toLong)))
      val missing = Iterator
        .iterate(firstDay)(_.plusDays(1))
        .takeWhile(!_.isAfter(lastDay))
        .filterNot(existing.contains)
        .toSeq
      (existing, missing)
    }

  private def dayOf(ts: CantonTimestamp): LocalDate =
    LocalDate.ofInstant(ts.toInstant, ZoneOffset.UTC)
}
