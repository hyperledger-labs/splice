// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.MetricsContext
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, UpdateHistory}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class DeleteCorruptAcsSnapshotTrigger(
    store: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
    // we always return 1 task, so PollingParallelTaskExecutionTrigger in effect does nothing in parallel
) extends PollingParallelTaskExecutionTrigger[DeleteCorruptAcsSnapshotTrigger.Task] {

  private val historyMetrics = new HistoryMetrics(context.metricsFactory)(MetricsContext.Empty)

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[DeleteCorruptAcsSnapshotTrigger.Task]] = {
    if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else if (updateHistory.corruptAcsSnapshotsDeleted) {
      Future.successful(Seq.empty)
    } else {
      for {
        migrations <- updateHistory.migrationsWithCorruptSnapshots()
      } yield migrations.lastOption match {
        case Some(migrationToClean) =>
          historyMetrics.CorruptAcsSnapshots.completed.updateValue(0)
          Seq(DeleteCorruptAcsSnapshotTrigger.Task(migrationToClean))
        case None =>
          updateHistory.markCorruptAcsSnapshotsDeleted()
          historyMetrics.CorruptAcsSnapshots.completed.updateValue(1)
          Seq.empty
      }
    }
  }

  override protected def completeTask(task: DeleteCorruptAcsSnapshotTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case DeleteCorruptAcsSnapshotTrigger.Task(migrationId) =>
      for {
        lastSnapshotO <- store.lookupSnapshotBefore(migrationId, CantonTimestamp.MaxValue)
        lastSnapshot = lastSnapshotO.getOrElse(
          throw new RuntimeException("Task should never become stale")
        )
        _ <- store.deleteSnapshot(lastSnapshot)
      } yield {
        historyMetrics.CorruptAcsSnapshots.count.inc()
        historyMetrics.CorruptAcsSnapshots.latestRecordTime.updateValue(
          lastSnapshot.snapshotRecordTime.toMicros
        )
        TaskSuccess(
          s"Successfully deleted snapshot $lastSnapshot."
        )
      }
  }

  override protected def isStaleTask(task: DeleteCorruptAcsSnapshotTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)
}

object DeleteCorruptAcsSnapshotTrigger {

  case class Task(
      migrationId: Long
  ) extends PrettyPrinting {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("migrationId", _.migrationId)
    )
  }

}
