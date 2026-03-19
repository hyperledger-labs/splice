// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.Timed
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskNoop,
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
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import org.lfdecentralizedtrust.splice.store.HistoryMetrics.AcsSnapshotsMetrics

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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

  protected def snapshotMetrics: AcsSnapshotsMetrics

  // The time interval to process per trigger invocation.
  // Setting this to a large value allows snapshot generation to catch up faster when it's behind,
  // but also increases the risk of timeouts and long-running transactions.
  protected val updateInterval: java.time.Duration =
    context.config.acsSnapshotTriggerPollingInterval
      .getOrElse(context.config.pollingInterval)
      .asJava

  override final def completeTask(task: AcsSnapshotTriggerBase.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = (task match {
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
      Timed
        .future(
          snapshotMetrics.latencyUpdate,
          store
            .updateIncrementalSnapshot(
              table = snapshotTable,
              snapshot = snapshot,
              targetRecordTime = updateUntil,
            ),
        )
        .map(_ => {
          snapshotMetrics.latestRecordTimeUpdate.updateValue(updateUntil)
          TaskSuccess(s"Updated incremental snapshot to $updateUntil")
        })
    case AcsSnapshotTriggerBase.SaveIncrementalSnapshotTask(snapshot, nextAt) =>
      Timed
        .future(
          snapshotMetrics.latencySave,
          store
            .saveIncrementalSnapshot(
              table = snapshotTable,
              snapshot = snapshot,
              nextSnapshotTargetRecordTime = nextAt,
            ),
        )
        .map(_ => {
          snapshotMetrics.latestRecordTimeSave.updateValue(snapshot.recordTime)
          TaskSuccess(s"Saved incremental snapshot at ${snapshot.recordTime}")
        })
    case AcsSnapshotTriggerBase.DeleteIncrementalSnapshotTask(snapshot) =>
      store
        .deleteIncrementalSnapshot(
          table = snapshotTable,
          snapshot = snapshot,
        )
        .map(_ => TaskSuccess(s"Deleted incremental snapshot"))
  }).transform {
    case Success(result) =>
      snapshotMetrics.waitingForLock.updateValue(0)
      Success(result)
    case Failure(e: AcsSnapshotStore.FailedToAcquireLockException) =>
      // It is expected that we sometimes fail to acquire the lock on the snapshot table.
      // The time until the lock is released is typically much larger than our task retry timeouts,
      // so we can just silently skip the task and try again later.
      snapshotMetrics.waitingForLock.updateValue(1)
      logger.info(s"Skipping $task", e)
      Success(TaskNoop)
    case Failure(ex) =>
      Failure(ex)
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

  protected def getMinRecordTime(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] = {
    updateHistory.getRecordTimeRange(migrationId).map(_.map(_.min))
  }

  protected def getMaxRecordTime(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] = {
    updateHistory.getRecordTimeRange(migrationId).map(_.map(_.max))
  }

  protected def getLastIngestedRecordTime(migrationId: Long): Option[CantonTimestamp] = {
    if (migrationId == updateHistory.domainMigrationInfo.currentMigrationId) {
      updateHistory.lastIngestedRecordTime
    } else {
      Some(CantonTimestamp.MaxValue)
    }
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

  sealed trait RetrieveTaskForMigrationResult
  object RetrieveTaskForMigrationResult {

    /** Nothing to do at the moment, as we're waiting for some external process. Retry later. */
    case object Waiting extends RetrieveTaskForMigrationResult

    case object ReachedMigrationEnd extends RetrieveTaskForMigrationResult

    /** Execute this task and poll immediately for the next task. */
    final case class Task(task: AcsSnapshotTriggerBase.Task) extends RetrieveTaskForMigrationResult
  }

  /** Determines the next task to perform in order to update the incremental snapshot for the given migration.
    *
    * @param migrationId the migration for which to retrieve the task
    * @param isHistoryBackfilled check if the history backfilling is complete for a given migration
    * @param getIncrementalSnapshot the current incremental snapshot, if it exists
    * @param getLatestSnapshot the latest full snapshot for a given migration
    * @param getMinRecordTime the record time of the first non-import update for a given migration
    * @param getMaxRecordTime the record time of the last update for a given migration (use CantonTimestamp.MaxValue if the migration is still ongoing)
    * @param getLastIngestedRecordTime the last ingested record time for a given migration (use CantonTimestamp.MaxValue if ingestion has finished for the migration)
    * @param storageConfig configuration for snapshot storage, used to compute target times for snapshots
    * @param updateInterval the time interval to process per update task
    * @param logger logger for logging information and errors
    * @return a future containing either a task to execute or an indication that we're waiting for an external process
    */
  def retrieveTaskForMigration(
      migrationId: Long,
      isHistoryBackfilled: (Long) => Future[Boolean],
      getIncrementalSnapshot: () => Future[Option[IncrementalAcsSnapshot]],
      getLatestSnapshot: (Long) => Future[Option[AcsSnapshot]],
      getMinRecordTime: (Long) => Future[Option[CantonTimestamp]],
      getMaxRecordTime: (Long) => Future[Option[CantonTimestamp]],
      getLastIngestedRecordTime: (Long) => Option[CantonTimestamp],
      storageConfig: ScanStorageConfig,
      updateInterval: java.time.Duration,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[RetrieveTaskForMigrationResult] = {
    isHistoryBackfilled(migrationId).flatMap {
      case false =>
        // UpdateHistoryBackfillingTrigger is still running for this migration,
        // wait until it's done.
        Future.successful(RetrieveTaskForMigrationResult.Waiting)
      case true =>
        getIncrementalSnapshot().flatMap {
          case None =>
            getLatestSnapshot(migrationId).flatMap {
              case Some(latestSnapshot) =>
                // No incremental snapshot exists, start by copying from the latest full snapshot
                retrieveTaskFromFullSnapshot(
                  latestSnapshot,
                  migrationId,
                  getMaxRecordTime,
                  storageConfig,
                  logger,
                )
              case None =>
                // No full snapshot exists either, initialize an incremental snapshot from import updates
                retrieveTaskFromImportUpdates(
                  migrationId,
                  getMinRecordTime,
                  getMaxRecordTime,
                  storageConfig,
                  logger,
                )
            }
          case Some(incrementalSnapshot) =>
            // Continue working on the existing incremental snapshot
            retrieveTaskUpdateIncrementalSnapshot(
              migrationId,
              incrementalSnapshot,
              getMaxRecordTime,
              getLastIngestedRecordTime,
              storageConfig,
              updateInterval,
              logger,
            )
        }
    }
  }

  private def retrieveTaskFromFullSnapshot(
      latestSnapshot: AcsSnapshot,
      migrationId: Long,
      getMaxRecordTime: (Long) => Future[Option[CantonTimestamp]],
      storageConfig: ScanStorageConfig,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[RetrieveTaskForMigrationResult] = {
    val nextSnapshotTime = storageConfig.nextSnapshotTime(latestSnapshot)
    getMaxRecordTime(migrationId).map {
      case Some(maxRecordTime) if nextSnapshotTime.isAfter(maxRecordTime) =>
        logger.info(
          s"The existing full snapshot $latestSnapshot is already the last one for migration $migrationId."
        )
        RetrieveTaskForMigrationResult.ReachedMigrationEnd
      case _ =>
        logger.info(
          s"The last full snapshot $latestSnapshot is not yet at the end of migration $migrationId."
        )
        RetrieveTaskForMigrationResult.Task(
          AcsSnapshotTriggerBase.InitializeIncrementalSnapshotTask(
            from = latestSnapshot,
            nextAt = nextSnapshotTime,
          )
        )
    }
  }

  private def retrieveTaskFromImportUpdates(
      migrationId: Long,
      getMinRecordTime: (Long) => Future[Option[CantonTimestamp]],
      getMaxRecordTime: (Long) => Future[Option[CantonTimestamp]],
      storageConfig: ScanStorageConfig,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[RetrieveTaskForMigrationResult] = {
    getMinRecordTime(migrationId).flatMap {
      case Some(minRecordTime) =>
        // Set the time of the incremental snapshot to right before the first real (non-import) update of the current migration.
        val emptySnapshotRecordTime = minRecordTime.minusSeconds(1L)
        val nextSnapshotTime =
          storageConfig.computeDbSnapshotTimeAfter(minRecordTime)
        // Note: since there is a non-import update, we know that we have finished
        // ingesting import updates for this migration. It's safe to initialize
        // the snapshot from import updates now.
        getMaxRecordTime(migrationId).map {
          case Some(maxRecordTime) if nextSnapshotTime.isAfter(maxRecordTime) =>
            logger.info(
              s"Migration $migrationId is too short for an acs snapshot." +
                s"The first update is at $minRecordTime and the first snapshot would be at $nextSnapshotTime which is beyond the end of the migration $maxRecordTime."
            )
            RetrieveTaskForMigrationResult.ReachedMigrationEnd
          case _ =>
            RetrieveTaskForMigrationResult.Task(
              AcsSnapshotTriggerBase.InitializeIncrementalSnapshotFromImportUpdatesTask(
                recordTime = emptySnapshotRecordTime,
                migration = migrationId,
                nextAt = nextSnapshotTime,
              )
            )
        }
      case None =>
        // No updates exist yet for the current migration.
        logger.info(
          s"No updates other than ACS imports found. Retrying snapshot creation later."
        )
        Future.successful(RetrieveTaskForMigrationResult.Waiting)
    }

  }

  private def retrieveTaskUpdateIncrementalSnapshot(
      migrationId: Long,
      incrementalSnapshot: IncrementalAcsSnapshot,
      getMaxRecordTime: (Long) => Future[Option[CantonTimestamp]],
      getLastIngestedRecordTime: (Long) => Option[CantonTimestamp],
      storageConfig: ScanStorageConfig,
      updateInterval: java.time.Duration,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[RetrieveTaskForMigrationResult] = {
    if (incrementalSnapshot.migrationId != migrationId) {
      // Incremental snapshot contains data from a wrong migration, delete it and start over.
      // This happens when we switch migrations (e.g., during backfilling).
      logger.info(
        s"Existing incremental snapshot $incrementalSnapshot is not for migration $migrationId."
      )
      Future.successful(
        RetrieveTaskForMigrationResult.Task(
          AcsSnapshotTriggerBase.DeleteIncrementalSnapshotTask(incrementalSnapshot)
        )
      )
    } else {
      // Note: the code below makes sure that `recordTime` never moves past `targetRecordTime`.
      assert(!incrementalSnapshot.recordTime.isAfter(incrementalSnapshot.targetRecordTime))
      if (incrementalSnapshot.recordTime == incrementalSnapshot.targetRecordTime) {
        // Incremental snapshot is complete, copy it to historical storage.
        val nextSnapshotTime = storageConfig.nextSnapshotTime(incrementalSnapshot)
        logger.info(s"Incremental snapshot $incrementalSnapshot has reached its target time.")
        Future.successful(
          RetrieveTaskForMigrationResult.Task(
            AcsSnapshotTriggerBase.SaveIncrementalSnapshotTask(
              incrementalSnapshot,
              nextSnapshotTime,
            )
          )
        )
      } else {
        // Incremental snapshot is not yet complete.
        // The intent is that we process around 30sec (the update interval) of updates per iteration.
        // The target time for the update is therefore computed
        // from the state of the incremental snapshot (and not from the current time).
        val updateUntil = incrementalSnapshot.recordTime
          .plus(updateInterval)
          .min(
            incrementalSnapshot.targetRecordTime
          ) // Don't move past the target record time.

        // Make sure we don't move past the end of the migration or past the last ingested update.
        for {
          maxRecordTimeO <- getMaxRecordTime(migrationId)
          lastIngestedRecordTimeO = getLastIngestedRecordTime(migrationId)
        } yield (lastIngestedRecordTimeO, maxRecordTimeO) match {
          case (Some(lastIngestedRecordTime), _) if updateUntil.isAfter(lastIngestedRecordTime) =>
            logger.debug(
              s"Updating $incrementalSnapshot to $updateUntil would move past the last ingested record time for migration $migrationId at $lastIngestedRecordTime."
            )
            RetrieveTaskForMigrationResult.ReachedMigrationEnd
          case (_, Some(maxRecordTime))
              if incrementalSnapshot.targetRecordTime.isAfter(maxRecordTime) =>
            logger.debug(
              s"Snapshot $incrementalSnapshot has a target time beyond the end of migration $migrationId at $maxRecordTime."
            )
            RetrieveTaskForMigrationResult.ReachedMigrationEnd
          case (Some(_), Some(_)) =>
            RetrieveTaskForMigrationResult.Task(
              AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(
                incrementalSnapshot,
                updateUntil,
              )
            )
          case (a, b) =>
            // Happens when the last ingested record time is not initialized yet.
            logger.debug(s"Max record time for update ($a) or save ($b) not available yet.")
            RetrieveTaskForMigrationResult.Waiting
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
