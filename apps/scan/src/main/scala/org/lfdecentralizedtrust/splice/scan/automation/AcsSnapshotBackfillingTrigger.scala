// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.IncrementalAcsSnapshotTable
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotBackfillingTrigger.State
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTriggerBase.{
  DeleteIncrementalSnapshotTask,
  InitializeEmptyIncrementalSnapshotTask,
  InitializeIncrementalSnapshotTask,
  SaveIncrementalSnapshotTask,
  UpdateIncrementalSnapshotTask,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig

import java.util.concurrent.atomic.AtomicReference
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
) extends AcsSnapshotTriggerBase(store, updateHistory, storageConfig, context) {

  override val snapshotTable: IncrementalAcsSnapshotTable =
    AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill

  private val state: AtomicReference[Option[State]] =
    new AtomicReference(None)

  private[automation] def isDoneBackfillingAcsSnapshots =
    state.get().contains(State.Done)
  private[automation] def currentBackfillingMigrationId: Option[Long] =
    state.get() match {
      case Some(State.WorkingOnMigrationId(migrationId)) => Some(migrationId)
      case _ => None
    }

  private def getState()(implicit
      tc: TraceContext
  ): Future[State] = state.get() match {
    case None => initializeState()
    case Some(s) => Future.successful(s)
  }

  private def initializeState()(implicit
      tc: TraceContext
  ): Future[State] = {
    store.getIncrementalSnapshot(snapshotTable).flatMap {
      // There is an existing incremental snapshot, resume from there
      case Some(snapshot) =>
        logger.info(
          s"Resuming backfilling of ACS snapshots from migration id ${snapshot.migrationId}."
        )
        val result = State.WorkingOnMigrationId(snapshot.migrationId)
        state.set(Some(result))
        Future.successful(result)
      case None =>
        // No existing incremental snapshot, start from the migration before the current one
        updateHistory.getPreviousMigrationId(store.currentMigrationId).map {
          case None =>
            logger.info("No migrations to backfill.")
            val result = State.Done
            state.set(Some(result))
            result
          case Some(migrationIdToBackfill) =>
            logger.info(
              s"Starting backfilling of ACS snapshots from migration id $migrationIdToBackfill."
            )
            val result = State.WorkingOnMigrationId(migrationIdToBackfill)
            state.set(Some(result))
            result
        }
    }
  }

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AcsSnapshotTriggerBase.Task]] = {
    if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else {
      getState().flatMap {
        case State.Done =>
          Future.successful(Seq.empty)
        case State.WorkingOnMigrationId(migrationId) =>
          retrieveTaskForPastMigrationId(migrationId).map(_.toList)
      }
    }
  }

  private def checkForMigrationEnd(
      task: AcsSnapshotTriggerBase.Task,
      migrationId: Long,
      nextAt: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[Option[AcsSnapshotTriggerBase.Task]] = {
    for {
      maxTime <- lastUpdateRecordTimeForMigration(migrationId)
      result <-
        if (nextAt.isAfter(maxTime)) {
          // Avoid creating the last snapshot for past migration ids, which will contain a portion of empty history.
          // This is important because, if the migration id gets restored after HDM fast enough,
          // said snapshot might be invalid.
          logger.info(
            s"Backfilling of migration id $migrationId is complete."
          )
          updateHistory.getPreviousMigrationId(migrationId).map {
            case Some(previousMigrationId) =>
              logger.info(
                s"Continuing backfilling of ACS snapshots from migration id $previousMigrationId."
              )
              state.set(Some(State.WorkingOnMigrationId(previousMigrationId)))
              // At this point we could search for the next task for the previous migration id,
              // but to keep the logic simple, we just return no task for now and let the next poll pick it up.
              None
            case None =>
              logger.info("Backfilling of ACS snapshots is complete.")
              state.set(Some(State.Done))
              None
          }
        } else {
          Future.successful(Some(task))
        }
    } yield result
  }

  private def retrieveTaskForPastMigrationId(migrationIdToBackfill: Long)(implicit
      tc: TraceContext
  ): Future[Option[AcsSnapshotTriggerBase.Task]] = {
    retrieveTaskForMigration(
      migrationId = migrationIdToBackfill,
      historyIngestedUntil = Some(CantonTimestamp.MaxValue),
    ).flatMap {
      // `retrieveTaskForMigration()` will never stop creating snapshots for the given migration id.
      // For backfilling however, we need to check when we have reached the end of the current migration.
      // We do this by aborting any task that would start work on a snapshot with a target record time
      // after the last update record time.
      case Some(task @ InitializeEmptyIncrementalSnapshotTask(_, _, nextAt)) =>
        // If we reach the end here, it means the migration was too short for a single snapshot.
        checkForMigrationEnd(task, migrationIdToBackfill, nextAt)
      case Some(task @ InitializeIncrementalSnapshotTask(_, nextAt)) =>
        // If we reach the end here, it means all snapshots for this migration have already been created elsewhere.
        checkForMigrationEnd(task, migrationIdToBackfill, nextAt)
      case Some(task @ UpdateIncrementalSnapshotTask(snapshot, _, _)) =>
        // If we reach the end here, it means the incremental backfilling has just arrived at the end.
        checkForMigrationEnd(task, migrationIdToBackfill, snapshot.targetRecordTime)
      case Some(task @ DeleteIncrementalSnapshotTask(_)) =>
        Future.successful(Some(task))
      case Some(task @ SaveIncrementalSnapshotTask(_, _)) =>
        Future.successful(Some(task))
      case None => Future.successful(None)
    }
  }

}

object AcsSnapshotBackfillingTrigger {
  sealed trait State
  object State {
    case class WorkingOnMigrationId(migrationId: Long) extends State
    case object Done extends State
  }
}
