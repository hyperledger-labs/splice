// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import cats.data.OptionT
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
import org.lfdecentralizedtrust.splice.store.{PageLimit, UpdateHistory}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.Done
import org.apache.pekko.stream.Materializer

import java.time.temporal.ChronoField
import java.time.{Duration, ZoneOffset}
import cats.implicits.*
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotTrigger2(
    store: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    storageConfig: ScanStorageConfig,
    currentMigrationId: Long,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
    // we always return 1 task, so PollingParallelTaskExecutionTrigger in effect does nothing in parallel
) extends PollingParallelTaskExecutionTrigger[AcsSnapshotTrigger2.Task] {

  protected val snapshotTable: IncrementalAcsSnapshotTable =
    AcsSnapshotStore.IncrementalAcsSnapshotTable.Next

  override final def completeTask(task: AcsSnapshotTrigger2.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case AcsSnapshotTrigger2.InitializeIncrementalSnapshotTask(from, nextAt) =>
      store
        .initializeIncrementalSnapshot(
          table = snapshotTable,
          initializeFrom = from,
          targetRecordTime = nextAt,
        )
        .map(_ => TaskSuccess(s"Initialized incremental snapshot from $from"))
    case AcsSnapshotTrigger2.InitializeEmptyIncrementalSnapshotTask(
          recordTime,
          migration,
          nextAt,
        ) =>
      store
        .initializeEmptyIncrementalSnapshot(
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
    case AcsSnapshotTrigger2.UpdateIncrementalSnapshotTask(snapshot, _, updateUntil) =>
      store
        .updateIncrementalSnapshot(
          table = snapshotTable,
          snapshot = snapshot,
          targetRecordTime = updateUntil,
        )
        .map(_ => TaskSuccess(s"Updated incremental snapshot to $updateUntil"))
    case AcsSnapshotTrigger2.SaveIncrementalSnapshotTask(snapshot, nextAt) =>
      store
        .saveIncrementalSnapshot(
          table = snapshotTable,
          snapshot = snapshot,
          nextSnapshotTargetRecordTime = nextAt,
        )
        .map(_ => TaskSuccess(s"Saved incremental snapshot at ${snapshot.recordTime}"))
  }

  override final def isStaleTask(task: AcsSnapshotTrigger2.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = for {
    currentSnapshot <- store.getIncrementalSnapshot(snapshotTable)
  } yield task match {
    case AcsSnapshotTrigger2.UpdateIncrementalSnapshotTask(snapshot, _, _) =>
      currentSnapshot.forall(_.recordTime != snapshot.recordTime)
    case AcsSnapshotTrigger2.SaveIncrementalSnapshotTask(snapshot, _) =>
      currentSnapshot.forall(_.targetRecordTime != snapshot.targetRecordTime)
    case AcsSnapshotTrigger2.InitializeEmptyIncrementalSnapshotTask(_, _, _) =>
      currentSnapshot.isDefined
    case AcsSnapshotTrigger2.InitializeIncrementalSnapshotTask(_, _) =>
      currentSnapshot.isDefined
  }

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AcsSnapshotTrigger2.Task]] = {
    if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else {
      isHistoryBackfilled(currentMigrationId).flatMap { backfilled =>
        if (backfilled) {
          retrieveTask().map(_.toList)
        } else {
          Future.successful(Seq.empty)
        }
      }
    }
  }

  private def retrieveTask()(implicit
      tc: TraceContext
  ): Future[Option[AcsSnapshotTrigger2.Task]] = {
    store.getIncrementalSnapshot(snapshotTable).flatMap {
      case None =>
        store.lookupSnapshotAtOrBefore(currentMigrationId, CantonTimestamp.MaxValue).flatMap {
          // No incremental snapshot exists, start by copying from the latest full snapshot
          case Some(latestSnapshot) =>
            val nextSnapshotTime = storageConfig.nextSnapshotTime(latestSnapshot)
            Future.successful(
              Some(
                AcsSnapshotTrigger2.InitializeIncrementalSnapshotTask(
                  from = latestSnapshot,
                  nextAt = nextSnapshotTime,
                )
              )
            )
          case None =>
            // No full snapshot exists either, start an empty incremental snapshot right before the
            // first real (non-import) update of the current migration.
            firstUpdateRecordTimeForMigration(currentMigrationId).map {
              case Some(firstUpdateRecordTime) =>
                val emptySnapshotRecordTime = firstUpdateRecordTime.minusSeconds(1L)
                val nextSnapshotTime = storageConfig.computeSnapshotTimeAfter(firstUpdateRecordTime)
                Some(
                  AcsSnapshotTrigger2.InitializeEmptyIncrementalSnapshotTask(
                    recordTime = emptySnapshotRecordTime,
                    migration = currentMigrationId,
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
        assert(!snapshot.recordTime.isAfter(snapshot.targetRecordTime))
        if (snapshot.recordTime == snapshot.targetRecordTime) {
          // Incremental snapshot is complete, copy it to historical storage
          val nextSnapshotTime = storageConfig.nextSnapshotTime(snapshot)
          Future.successful(
            Some(AcsSnapshotTrigger2.SaveIncrementalSnapshotTask(snapshot, nextSnapshotTime))
          )
        } else {
          // Incremental snapshot is not yet complete.
          // The intent is that we process around 30sec (the polling interval) of updates per iteration.

          // First, make sure we don't process too much. The target time for the update is computed
          // from the state of the incremental snapshot (and not from the current time).
          val updateUntil = snapshot.recordTime
            .plus(context.config.pollingInterval.asJava)
            .min(snapshot.targetRecordTime)

          // Next, make sure we don't process too little. We don't want this trigger to busy loop
          // doing tiny updates, so only run the update if there is a full chunk of data to process.
          val now = context.clock.now
          if (now.isAfter(updateUntil)) {
            Future.successful(
              Some(AcsSnapshotTrigger2.UpdateIncrementalSnapshotTask(snapshot, now, updateUntil))
            )
          } else {
            Future.successful(None)
          }
        }
    }
  }

  /** @return True if the passed migration id was fully backfilled.
    *         This applies to the current migration id, where it either didn't need to backfill,
    *         or backfilled because it joined late.
    *         And also for past migrations, whether the SV was present in them or not.
    */
  protected def isHistoryBackfilled(
      migrationId: Long
  )(implicit tc: TraceContext): Future[Boolean] = {
    updateHistory.sourceHistory
      .migrationInfo(migrationId)
      .map(_.exists(i => i.complete && i.importUpdatesComplete))
  }

  protected def firstUpdateRecordTimeForMigration(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] = {
    updateHistory
      .getUpdatesWithoutImportUpdates(
        Some(
          (
            migrationId,
            CantonTimestamp.MinValue,
          )
        ),
        PageLimit.tryCreate(1),
      )
      .map(_.headOption)
      .map(_.map(_.update.update.recordTime))
  }
}

object AcsSnapshotTrigger2 {
  sealed trait Task extends PrettyPrinting
  case class InitializeIncrementalSnapshotTask(from: AcsSnapshot, nextAt: CantonTimestamp)
      extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("from", _.from),
        param("nextAt", _.nextAt),
      )
  }
  case class InitializeEmptyIncrementalSnapshotTask(
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
      now: CantonTimestamp,
      updateUntil: CantonTimestamp,
  ) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("snapshot", _.snapshot),
        param("now", _.snapshot),
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
}
