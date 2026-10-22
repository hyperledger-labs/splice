// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.UpdateHistory

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

class UpdateHistoryPartitionMaintenanceTrigger(
  updateHistory: UpdateHistory,
  override protected val context: TriggerContext,
)(implicit
  ec: ExecutionContext,
  override val tracer: Tracer,
  mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[UpdateHistoryPartitionMaintenanceTrigger.Task] {
  import UpdateHistoryPartitionMaintenanceTrigger.*

  override protected def retrieveTasks()(implicit
    tc: TraceContext
  ): Future[Seq[Task]] =
    if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else {
      updateHistory.missingPartitionDays(context.clock.now).map(_.map(Task(_)))
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

object UpdateHistoryPartitionMaintenanceTrigger {

  final case class Task(day: LocalDate) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(param("day", _.day.toString.unquoted))
  }
}
