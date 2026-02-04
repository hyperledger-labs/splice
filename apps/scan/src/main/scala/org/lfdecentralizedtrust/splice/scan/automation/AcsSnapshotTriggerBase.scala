// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  IncrementalAcsSnapshot,
  IncrementalAcsSnapshotTable,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.util.DomainRecordTimeRange

import scala.concurrent.{ExecutionContext, Future}

abstract class AcsSnapshotTriggerBase(
    store: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
    // we always return 1 task, so PollingParallelTaskExecutionTrigger in effect does nothing in parallel
) extends PollingParallelTaskExecutionTrigger[AcsSnapshotTriggerBase.Task] {

  protected val snapshotTable: IncrementalAcsSnapshotTable

  override final def completeTask(task: AcsSnapshotTriggerBase.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case AcsSnapshotTriggerBase.InitializeIncrementalSnapshotTask(from, nextAt) =>
      store
        .initializeIncrementalSnapshot(
          table = snapshotTable,
          initializeFrom = from,
          targetRecordTime = nextAt,
        )
        .map(_ => TaskSuccess(s"Initialized incremental snapshot from $from"))
    case AcsSnapshotTriggerBase.InitializeIncrementalSnapshotFromImportUpdatesTask(
          recordTime,
          migration,
          nextAt,
        ) =>
      store
        .initializeIncrementalSnapshotFromImportUpdates(
          table = snapshotTable,
          recordTime = recordTime,
          targetRecordTime = nextAt,
          migrationId = migration,
        )
        .map(_ =>
          TaskSuccess(
            s"Initialized empty incremental snapshot at $recordTime in migration $migration"
          )
        )
    case AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(snapshot, updateUntil) =>
      store
        .updateIncrementalSnapshot(
          table = snapshotTable,
          snapshot = snapshot,
          targetRecordTime = updateUntil,
        )
        .map(_ => TaskSuccess(s"Updated incremental snapshot to $updateUntil"))
    case AcsSnapshotTriggerBase.SaveIncrementalSnapshotTask(snapshot, nextAt) =>
      store
        .saveIncrementalSnapshot(
          table = snapshotTable,
          snapshot = snapshot,
          nextSnapshotTargetRecordTime = nextAt,
        )
        .map(_ => TaskSuccess(s"Saved incremental snapshot at ${snapshot.recordTime}"))
    case AcsSnapshotTriggerBase.DeleteIncrementalSnapshotTask(snapshot) =>
      store
        .deleteIncrementalSnapshot(
          table = snapshotTable,
          snapshot = snapshot,
        )
        .map(_ => TaskSuccess(s"Deleted incremental snapshot"))
  }

  override final def isStaleTask(task: AcsSnapshotTriggerBase.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = for {
    currentSnapshot <- store.getIncrementalSnapshot(snapshotTable)
  } yield task match {
    case AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(snapshot, _) =>
      !currentSnapshot.contains(snapshot)
    case AcsSnapshotTriggerBase.SaveIncrementalSnapshotTask(snapshot, _) =>
      !currentSnapshot.contains(snapshot)
    case AcsSnapshotTriggerBase.InitializeIncrementalSnapshotFromImportUpdatesTask(_, _, _) =>
      currentSnapshot.isDefined
    case AcsSnapshotTriggerBase.InitializeIncrementalSnapshotTask(_, _) =>
      currentSnapshot.isDefined
    case AcsSnapshotTriggerBase.DeleteIncrementalSnapshotTask(_) =>
      currentSnapshot.isEmpty
  }

  protected def getLatestSnapshot(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[AcsSnapshot]] = {
    store.lookupSnapshotAtOrBefore(migrationId, CantonTimestamp.MaxValue)
  }

  protected def getRecordTimeRange(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[DomainRecordTimeRange]] = {
    updateHistory.getRecordTimeRange(migrationId)
  }

  protected def getPreviousMigrationId(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {
    updateHistory.getPreviousMigrationId(migrationId)
  }

  protected def getIncrementalSnapshot()(implicit
      tc: TraceContext
  ): Future[Option[IncrementalAcsSnapshot]] = {
    store.getIncrementalSnapshot(snapshotTable)
  }
}

object AcsSnapshotTriggerBase {

  /** Determines the next task to perform in order to update the incremental snapshot for the given migration.
    *
    * This method treats the given migration as having no end. The caller is responsible for
    * checking that the returned task does not move beyond the end of the migration:
    * - AcsSnapshotTrigger should check that the snapshot does not move beyond the last ingested record time.
    * - AcsSnapshotBackfillingTrigger should check that the snapshot does not move beyond the end of the migration.
    *
    * Returns None if there is no task to perform at the moment (but there may be in the future).
    */
  def retrieveTaskForMigration(
      migrationId: Long,
      isHistoryBackfilled: (Long) => Future[Boolean],
      getIncrementalSnapshot: () => Future[Option[IncrementalAcsSnapshot]],
      getLatestSnapshot: (Long) => Future[Option[AcsSnapshot]],
      getRecordTimeRange: (Long) => Future[Option[DomainRecordTimeRange]],
      storageConfig: ScanStorageConfig,
      updateInterval: java.time.Duration,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[AcsSnapshotTriggerBase.Task]] = {
    isHistoryBackfilled(migrationId).flatMap {
      case false =>
        // UpdateHistoryBackfillingTrigger is still running for this migration,
        // wait until it's done.
        Future.successful(None)
      case true =>
        getIncrementalSnapshot().flatMap {
          case None =>
            getLatestSnapshot(migrationId).flatMap {
              // No incremental snapshot exists, start by copying from the latest full snapshot
              case Some(latestSnapshot) =>
                val nextSnapshotTime = storageConfig.nextSnapshotTime(latestSnapshot)
                Future.successful(
                  Some(
                    AcsSnapshotTriggerBase.InitializeIncrementalSnapshotTask(
                      from = latestSnapshot,
                      nextAt = nextSnapshotTime,
                    )
                  )
                )
              case None =>
                // No full snapshot exists either, initialize an incremental snapshot from
                // import updates and set the snapshot time to right before the
                // first real (non-import) update of the current migration.
                getRecordTimeRange(migrationId).map {
                  case Some(range) =>
                    val emptySnapshotRecordTime = range.min.minusSeconds(1L)
                    val nextSnapshotTime =
                      storageConfig.computeSnapshotTimeAfter(
                        range.min,
                        storageConfig.dbAcsSnapshotPeriodHours,
                      )
                    // Note: since there is a non-import update, we know that we have finished
                    // ingesting import updates for this migration. It's safe to initialize
                    // the snapshot from import updates now.
                    Some(
                      AcsSnapshotTriggerBase.InitializeIncrementalSnapshotFromImportUpdatesTask(
                        recordTime = emptySnapshotRecordTime,
                        migration = migrationId,
                        nextAt = nextSnapshotTime,
                      )
                    )
                  case None =>
                    // No updates exist for the current migration, so nothing to do.
                    logger.info(
                      s"No updates other than ACS imports found. Retrying snapshot creation later."
                    )
                    None
                }
            }
          case Some(snapshot) =>
            if (snapshot.migrationId != migrationId) {
              // Incremental snapshot contains data from a wrong migration, delete it and start over.
              Future.successful(
                Some(AcsSnapshotTriggerBase.DeleteIncrementalSnapshotTask(snapshot))
              )
            } else {
              // Note: the code below makes sure that `recordTime` never moves past `targetRecordTime`.
              assert(!snapshot.recordTime.isAfter(snapshot.targetRecordTime))
              if (snapshot.recordTime == snapshot.targetRecordTime) {
                // Incremental snapshot is complete, copy it to historical storage.
                val nextSnapshotTime = storageConfig.nextSnapshotTime(snapshot)
                Future.successful(
                  Some(
                    AcsSnapshotTriggerBase.SaveIncrementalSnapshotTask(snapshot, nextSnapshotTime)
                  )
                )
              } else {
                // Incremental snapshot is not yet complete.
                // The intent is that we process around 30sec (the update interval) of updates per iteration.
                // The target time for the update is therefore computed
                // from the state of the incremental snapshot (and not from the current time).
                val updateUntil = snapshot.recordTime
                  .plus(updateInterval)
                  .min(snapshot.targetRecordTime) // Don't move past the target record time.

                Future.successful(
                  Some(
                    AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(
                      snapshot,
                      updateUntil,
                    )
                  )
                )
              }
            }
        }
    }
  }

  sealed trait Task extends PrettyPrinting
  case class InitializeIncrementalSnapshotTask(from: AcsSnapshot, nextAt: CantonTimestamp)
      extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("from", _.from),
        param("nextAt", _.nextAt),
      )
  }
  case class InitializeIncrementalSnapshotFromImportUpdatesTask(
      recordTime: CantonTimestamp,
      migration: Long,
      nextAt: CantonTimestamp,
  ) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("recordTime", _.recordTime),
        param("migration", _.migration),
        param("nextAt", _.nextAt),
      )
  }
  case class UpdateIncrementalSnapshotTask(
      snapshot: IncrementalAcsSnapshot,
      updateUntil: CantonTimestamp,
  ) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("snapshot", _.snapshot),
        param("updateUntil", _.updateUntil),
      )
  }
  case class SaveIncrementalSnapshotTask(
      snapshot: IncrementalAcsSnapshot,
      nextAt: CantonTimestamp,
  ) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("snapshot", _.snapshot),
        param("nextAt", _.nextAt),
      )
  }
  case class DeleteIncrementalSnapshotTask(snapshot: IncrementalAcsSnapshot) extends Task {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("snapshot", _.snapshot)
    )
  }
}
