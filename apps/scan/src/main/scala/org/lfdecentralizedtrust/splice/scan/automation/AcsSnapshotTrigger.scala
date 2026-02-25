// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  IncrementalAcsSnapshot,
  IncrementalAcsSnapshotTable,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTriggerBase.{
  DeleteIncrementalSnapshotTask,
  InitializeIncrementalSnapshotFromImportUpdatesTask,
  InitializeIncrementalSnapshotTask,
  SaveIncrementalSnapshotTask,
  UpdateIncrementalSnapshotTask,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.util.DomainRecordTimeRange

import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotTrigger(
    store: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    storageConfig: ScanStorageConfig,
    override protected val context: TriggerContext,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
) extends AcsSnapshotTriggerBase(store, updateHistory, context) {

  override val snapshotTable: IncrementalAcsSnapshotTable =
    AcsSnapshotStore.IncrementalAcsSnapshotTable.Next

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AcsSnapshotTriggerBase.Task]] = {
    if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else {
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = store.currentMigrationId,
          isHistoryBackfilled = updateHistory.isHistoryBackfilled,
          lastIngestedRecordTime = updateHistory.lastIngestedRecordTime,
          getIncrementalSnapshot = () => getIncrementalSnapshot(),
          getLatestSnapshot = getLatestSnapshot,
          getRecordTimeRange = getRecordTimeRange,
          storageConfig = storageConfig,
          updateInterval = context.config.pollingInterval.asJava,
          logger = logger,
        )
    }
  }

}

object AcsSnapshotTrigger {

  def retrieveTaskForCurrentMigration(
      migrationId: Long,
      isHistoryBackfilled: (Long) => Future[Boolean],
      lastIngestedRecordTime: Option[CantonTimestamp],
      getIncrementalSnapshot: () => Future[Option[IncrementalAcsSnapshot]],
      getLatestSnapshot: (Long) => Future[Option[AcsSnapshot]],
      getRecordTimeRange: (Long) => Future[Option[DomainRecordTimeRange]],
      storageConfig: ScanStorageConfig,
      updateInterval: java.time.Duration,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[AcsSnapshotTriggerBase.Task]] = {
    AcsSnapshotTriggerBase
      .retrieveTaskForMigration(
        migrationId,
        isHistoryBackfilled,
        getIncrementalSnapshot,
        getLatestSnapshot,
        getRecordTimeRange,
        storageConfig,
        updateInterval,
        logger,
      )
      .map {
        case Some(task) if taskBeyondUpdateHistoryEnd(task, lastIngestedRecordTime) =>
          Seq.empty
        case Some(task) => Seq(task)
        case None => Seq.empty
      }
  }

  private def taskBeyondUpdateHistoryEnd(
      task: AcsSnapshotTriggerBase.Task,
      lastIngestedRecordTimeO: Option[CantonTimestamp],
  ): Boolean = {
    task match {
      case UpdateIncrementalSnapshotTask(_, updateUntil) =>
        // Don't move beyond the last ingested record time, otherwise we'll miss updates
        // between `lastIngestedRecordTime` and `updateUntil`.
        lastIngestedRecordTimeO.forall(updateUntil.isAfter)
      case InitializeIncrementalSnapshotFromImportUpdatesTask(_, _, _) =>
        false
      case InitializeIncrementalSnapshotTask(_, _) =>
        false
      case SaveIncrementalSnapshotTask(_, _) =>
        false
      case DeleteIncrementalSnapshotTask(_) =>
        false
    }
  }
}
