// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  IncrementalAcsSnapshot,
  IncrementalAcsSnapshotTable,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.Done
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

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotBackfillingTrigger(
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
    AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill

  private val isDone: AtomicBoolean = new AtomicBoolean(false)

  private[automation] def isDoneBackfillingAcsSnapshots =
    isDone.get()

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AcsSnapshotTriggerBase.Task]] = {
    if (isDone.get()) {
      Future.successful(Seq.empty)
    } else if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else {
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = store.currentMigrationId,
          isHistoryBackfilled = isHistoryBackfilled,
          getIncrementalSnapshot = () => getIncrementalSnapshot(),
          getLatestSnapshot = getLatestSnapshot,
          getRecordTimeRange = getRecordTimeRange,
          getPreviousMigrationId = getPreviousMigrationId,
          storageConfig = storageConfig,
          updateInterval = context.config.pollingInterval.asJava,
          logger: TracedLogger,
        )
        .flatMap {
          case Left(Done) =>
            logger.info(
              s"Backfilling incremental ACS snapshots is complete, this trigger won't do any work again."
            )
            isDone.set(true)
            Future.successful(Seq.empty)
          case Right(None) =>
            Future.successful(Seq.empty)
          case Right(Some(task)) =>
            Future.successful(Seq(task))
        }
    }
  }

}

object AcsSnapshotBackfillingTrigger {
  def retrieveTaskForBackfillingMigration(
      earliestKnownBackfilledMigrationId: Long,
      isHistoryBackfilled: (Long) => Future[Boolean],
      getIncrementalSnapshot: () => Future[Option[IncrementalAcsSnapshot]],
      getLatestSnapshot: (Long) => Future[Option[AcsSnapshot]],
      getRecordTimeRange: (Long) => Future[Option[DomainRecordTimeRange]],
      getPreviousMigrationId: (Long) => Future[Option[Long]],
      storageConfig: ScanStorageConfig,
      updateInterval: java.time.Duration,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Either[Done, Option[AcsSnapshotTriggerBase.Task]]] = {
    getIncrementalSnapshot().flatMap {
      case Some(incrementalSnapshot) =>
        getRecordTimeRange(incrementalSnapshot.migrationId).flatMap {
          case None =>
            logger.error(s"Incremental snapshot without update range: $incrementalSnapshot")
            Future.successful(Right(None))
          case Some(range) =>
            if (incrementalSnapshot.targetRecordTime.isAfter(range.max)) {
              // Incremental snapshot exists, but is beyond the end of the migration.
              // Delete it, and let the next iteration pick up the previous migration to start backfilling on.
              Future.successful(
                Right(
                  Some(
                    DeleteIncrementalSnapshotTask(
                      incrementalSnapshot
                    )
                  )
                )
              )
            } else {
              // Incremental snapshot exists and is within the migration range, continue updating it
              AcsSnapshotTriggerBase
                .retrieveTaskForMigration(
                  migrationId = incrementalSnapshot.migrationId,
                  isHistoryBackfilled = isHistoryBackfilled,
                  getIncrementalSnapshot = getIncrementalSnapshot,
                  getLatestSnapshot = getLatestSnapshot,
                  getRecordTimeRange = getRecordTimeRange,
                  storageConfig = storageConfig,
                  updateInterval = updateInterval,
                  logger = logger,
                )
                .map(Right(_))
            }
        }
      case None =>
        // There is no incremental snapshot. Find the right place to start backfilling.
        for {
          migrationIdToBackfillO <- getPreviousMigrationId(earliestKnownBackfilledMigrationId)
          task <- migrationIdToBackfillO match {
            case Some(migrationIdToBackfill) =>
              // There is a migration before the earliest known backfilled migration,
              // check if we can start backfilling on it.
              logger.debug(
                s"Earliest backfilled migration is $earliestKnownBackfilledMigrationId, looking tasks in migration $migrationIdToBackfill."
              )
              for {
                proposedTaskO <- AcsSnapshotTriggerBase.retrieveTaskForMigration(
                  migrationId = migrationIdToBackfill,
                  isHistoryBackfilled = isHistoryBackfilled,
                  storageConfig = storageConfig,
                  updateInterval = updateInterval,
                  getIncrementalSnapshot = getIncrementalSnapshot,
                  getLatestSnapshot = getLatestSnapshot,
                  getRecordTimeRange = getRecordTimeRange,
                  logger = logger,
                )
                rangeO <- getRecordTimeRange(migrationIdToBackfill)
                actualTask <- (proposedTaskO, rangeO) match {
                  case (Some(proposedTask), Some(range))
                      if taskBeyondMigrationEnd(proposedTask, range.max) =>
                    logger.info(
                      s"Next task $proposedTask would be beyond the end of migration $migrationIdToBackfill with record time range $range. Moving on to the next migration."
                    )
                    // Recursively look for tasks in the previous migration.
                    retrieveTaskForBackfillingMigration(
                      earliestKnownBackfilledMigrationId = migrationIdToBackfill,
                      isHistoryBackfilled = isHistoryBackfilled,
                      getIncrementalSnapshot = getIncrementalSnapshot,
                      getLatestSnapshot = getLatestSnapshot,
                      getRecordTimeRange = getRecordTimeRange,
                      getPreviousMigrationId = getPreviousMigrationId,
                      storageConfig = storageConfig,
                      updateInterval = updateInterval,
                      logger = logger,
                    )
                  case _ =>
                    Future.successful(Right(proposedTaskO))
                }
              } yield actualTask
            case None =>
              logger.info(
                s"No migrations found before migration $earliestKnownBackfilledMigrationId. Backfilling is complete."
              )
              Future.successful(Left(Done))
          }
        } yield task
    }
  }

  private def taskBeyondMigrationEnd(
      task: AcsSnapshotTriggerBase.Task,
      maxTime: CantonTimestamp,
  ): Boolean = {
    task match {
      case InitializeIncrementalSnapshotFromImportUpdatesTask(_, _, nextAt) =>
        // The migration was too short for a single snapshot
        nextAt.isAfter(maxTime)
      case InitializeIncrementalSnapshotTask(_, nextAt) =>
        // The migration is already fully backfilled
        nextAt.isAfter(maxTime)
      case UpdateIncrementalSnapshotTask(snapshot, _) =>
        // The incremental backfilling has just arrived at the end
        snapshot.targetRecordTime.isAfter(maxTime)
      case SaveIncrementalSnapshotTask(snapshot, _) =>
        // Should never happen, but just in case
        snapshot.targetRecordTime.isAfter(maxTime)
      case DeleteIncrementalSnapshotTask(_) =>
        false
    }
  }
}
