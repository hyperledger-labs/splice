// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.PerTableAcsSnapshot
import org.lfdecentralizedtrust.splice.store.db.AdvisoryLocks

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

class AcsSnapshotIndexTrigger(
    store: AcsSnapshotStore,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[PerTableAcsSnapshot] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PerTableAcsSnapshot]] = {
    if (store.updateHistory.isReady) {
      store.lookupOldestUnindexedSnapshot().map(_.toList)
    } else {
      Future.successful(Seq.empty)
    }
  }

  override protected def completeTask(task: PerTableAcsSnapshot)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = store
    .indexSnapshotStakeholdersTable(task)
    .map(_ => TaskSuccess(s"Successfully indexed tables of snapshot ${task.snapshotRecordTime}"))
    .transform {
      case Failure(e: AdvisoryLocks.FailedToAcquireLockException) =>
        // There was a concurrent DDL statement running.
        // The next `retrieveTasks` will still include the task.
        Success(TaskNoop)
      case other => other
    }

  override protected def isStaleTask(task: PerTableAcsSnapshot)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    store.lookupOldestUnindexedSnapshot().map {
      case None => false
      // if the oldest has changed, the task is stale
      case Some(oldest) => oldest.snapshotRecordTime != task.snapshotRecordTime
    }
  }

}
