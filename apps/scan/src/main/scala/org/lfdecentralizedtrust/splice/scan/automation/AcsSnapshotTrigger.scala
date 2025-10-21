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
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.AcsSnapshot
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

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotTrigger(
    store: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    snapshotPeriodHours: Int,
    updateHistoryBackfillEnabled: Boolean,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
    // we always return 1 task, so PollingParallelTaskExecutionTrigger in effect does nothing in parallel
) extends PollingParallelTaskExecutionTrigger[AcsSnapshotTrigger.Task] {

  private val timesToDoSnapshot = (0 to 23).filter(_ % snapshotPeriodHours == 0)
  private val currentMigrationId = store.currentMigrationId

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AcsSnapshotTrigger.Task]] = {
    if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else {
      isHistoryBackfilled(currentMigrationId).flatMap { backfilled =>
        if (backfilled) {
          retrieveTask().value.map(_.toList)
        } else {
          Future.successful(Seq.empty)
        }
      }
    }
  }

  /** @return True if the passed migration id was fully backfilled.
    *         This applies to the current migration id, where it either didn't need to backfill,
    *         or backfilled because it joined late.
    *         And also for past migrations, whether the SV was present in them or not.
    */
  private def isHistoryBackfilled(migrationId: Long)(implicit tc: TraceContext) = {
    updateHistory.sourceHistory
      .migrationInfo(migrationId)
      .map(_.exists(i => i.complete && i.importUpdatesComplete))
  }

  private def retrieveTask()(implicit
      tc: TraceContext
  ): OptionT[Future, AcsSnapshotTrigger.Task] = {
    val now = context.clock.now
    // prioritize filling the current migration id until there's no more tasks
    retrieveTaskForCurrentMigrationId(now).orElse {
      if (updateHistoryBackfillEnabled) {
        taskToContinueBackfillingACSSnapshots()
      } else {
        OptionT.none
      }
    }
  }

  private def nextSnapshotTime(lastSnapshot: AcsSnapshot) = {
    lastSnapshot.snapshotRecordTime.plus(Duration.ofHours(snapshotPeriodHours.toLong))
  }

  private def retrieveTaskForCurrentMigrationId(
      now: CantonTimestamp
  )(implicit tc: TraceContext): OptionT[Future, AcsSnapshotTrigger.Task] = {
    OptionT(for {
      lastSnapshot <- store.lookupSnapshotBefore(currentMigrationId, CantonTimestamp.MaxValue)
      possibleTask <- lastSnapshot match {
        case None =>
          firstSnapshotForMigrationIdTask(currentMigrationId)
        case Some(lastSnapshot) => // new snapshot should be created, if ACS for it is complete
          val newSnapshotRecordTime = nextSnapshotTime(lastSnapshot)
          Future.successful(
            Some(
              AcsSnapshotTrigger.Task(newSnapshotRecordTime, currentMigrationId, Some(lastSnapshot))
            )
          )
      }
      task <- possibleTask match {
        case None =>
          logger.info("No snapshots to take.")
          Future.successful(None)
        case Some(task) if task.snapshotRecordTime > now =>
          logger.info(
            s"Still not time to take a snapshot. Now: ${now}. Next snapshot time: ${task.snapshotRecordTime}."
          )
          Future.successful(None)
        case Some(task) =>
          updateHistory
            .getUpdatesWithoutImportUpdates(
              Some((currentMigrationId, task.snapshotRecordTime)),
              PageLimit.tryCreate(1),
            )
            .map(_.headOption)
            .map {
              case None =>
                logger.info("There might still be updates pending. Skipping snapshot creation.")
                None
              case Some(_) =>
                Some(task)
            }
      }
    } yield task)
  }

  private val lastCompleteBackfilledMigrationId: AtomicReference[Either[Done, Long]] =
    new AtomicReference(Right(currentMigrationId))
  def isDoneBackfillingAcsSnapshots = lastCompleteBackfilledMigrationId.get() == Left(Done)
  // backfilling is done from latest migration id to oldest
  private def taskToContinueBackfillingACSSnapshots()(implicit
      tc: TraceContext
  ): OptionT[Future, AcsSnapshotTrigger.Task] = {
    lastCompleteBackfilledMigrationId.get() match {
      case Left(Done) => // avoid unnecessary queries
        OptionT.none
      case Right(backfilledMigrationId) =>
        OptionT(updateHistory.getPreviousMigrationId(backfilledMigrationId).flatMap {
          case None =>
            logger.info("No more migrations to backfill.")
            lastCompleteBackfilledMigrationId.set(Left(Done))
            Future.successful(None)
          case Some(migrationIdToBackfill) =>
            isHistoryBackfilled(migrationIdToBackfill).flatMap { historyBackfilled =>
              if (historyBackfilled) {
                retrieveTaskForPastMigrationId(migrationIdToBackfill)
              } else {
                logger.info(
                  s"Migration id $migrationIdToBackfill does not yet have its history backfilled. Retrying backfill of ACS snapshot later."
                )
                Future.successful(None)
              }
            }
        })
    }
  }

  private def retrieveTaskForPastMigrationId(migrationIdToBackfill: Long)(implicit
      tc: TraceContext
  ): Future[Option[AcsSnapshotTrigger.Task]] = {
    for {
      migrationRecordTimeRange <- updateHistory.getRecordTimeRange(migrationIdToBackfill)
      maxTime = migrationRecordTimeRange
        .map(_._2.max)
        .maxOption
        .getOrElse(
          throw new IllegalStateException(
            s"SynchronizerId with no data in $migrationRecordTimeRange"
          )
        )
      minTime = migrationRecordTimeRange
        .map(_._2.min)
        .minOption
        .getOrElse(
          throw new IllegalStateException(
            s"SynchronizerId with no data in $migrationRecordTimeRange"
          )
        )
      firstSnapshotTime = computeFirstSnapshotTime(minTime)
      migrationLastedLongEnough = firstSnapshotTime
        .plus(Duration.ofHours(snapshotPeriodHours.toLong))
        .isBefore(maxTime)
      latestSnapshot <- store
        .lookupSnapshotBefore(migrationIdToBackfill, CantonTimestamp.MaxValue)
      task <- latestSnapshot match {
        // Avoid creating the last snapshot for past migration ids, which will be contain a portion of empty history.
        // This is important because, if the migration id gets restored after HDM fast enough,
        // said snapshot might be invalid.
        case Some(snapshot)
            if snapshot.snapshotRecordTime.plus(
              Duration.ofHours(snapshotPeriodHours.toLong)
            ) > maxTime =>
          logger.info(
            s"Backfilling of migration id $migrationIdToBackfill is complete. Trying with next oldest."
          )
          lastCompleteBackfilledMigrationId.set(Right(migrationIdToBackfill))
          taskToContinueBackfillingACSSnapshots().value
        case Some(snapshot) =>
          Future.successful(
            Some(
              AcsSnapshotTrigger
                .Task(nextSnapshotTime(snapshot), migrationIdToBackfill, Some(snapshot))
            )
          )
        case None if !migrationLastedLongEnough =>
          logger.info(
            s"Migration id $migrationIdToBackfill didn't last more than $snapshotPeriodHours hours (from $minTime to $maxTime), so it won't have any snapshots."
          )
          lastCompleteBackfilledMigrationId.set(Right(migrationIdToBackfill))
          taskToContinueBackfillingACSSnapshots().value
        case None =>
          firstSnapshotForMigrationIdTask(migrationIdToBackfill)
      }
    } yield task
  }

  override protected def completeTask(task: AcsSnapshotTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case AcsSnapshotTrigger.Task(snapshotRecordTime, migrationId, lastSnapshot) =>
      assert(task.snapshotRecordTime > CantonTimestamp.MinValue)
      store
        .insertNewSnapshot(lastSnapshot, migrationId, snapshotRecordTime)
        .map { insertCount =>
          if (insertCount == 0) {
            logger.error(
              s"No entries were inserted for by task $task on history ${updateHistory.historyId}. This is very likely a bug."
            )
          }
          TaskSuccess(
            s"Successfully inserted $insertCount entries by task $task on history ${updateHistory.historyId}."
          )
        }
  }

  override protected def isStaleTask(task: AcsSnapshotTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    store
      .lookupSnapshotBefore(currentMigrationId, task.snapshotRecordTime)
      .map(_.exists(_.snapshotRecordTime == task.snapshotRecordTime))
  }

  private def firstSnapshotForMigrationIdTask(migrationId: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[AcsSnapshotTrigger.Task]] = {
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
      .map {
        case None =>
          logger.info(s"No updates other than ACS imports found. Retrying snapshot creation later.")
          None
        case Some(firstNonAcsImport) =>
          val firstNonAcsImportRecordTime = firstNonAcsImport.update.update.recordTime
          Some(
            AcsSnapshotTrigger
              .Task(computeFirstSnapshotTime(firstNonAcsImportRecordTime), migrationId, None)
          )
      }
  }

  private def computeFirstSnapshotTime(firstNonAcsImportRecordTime: CantonTimestamp) = {
    val firstUpdateUTCTime = firstNonAcsImportRecordTime.toInstant.atOffset(ZoneOffset.UTC)
    val (hourForSnapshot, plusDays) = timesToDoSnapshot
      .find(_ > firstUpdateUTCTime.get(ChronoField.HOUR_OF_DAY)) match {
      case Some(hour) => hour -> 0 // current day at hour
      case None => 0 -> 1 // next day at 00:00
    }
    val until = firstUpdateUTCTime.toLocalDate
      .plusDays(plusDays.toLong)
      .atTime(hourForSnapshot, 0)
      .toInstant(ZoneOffset.UTC)
    CantonTimestamp.assertFromInstant(until)
  }
}

object AcsSnapshotTrigger {

  case class Task(
      snapshotRecordTime: CantonTimestamp,
      migrationId: Long,
      lastSnapshot: Option[AcsSnapshot],
  ) extends PrettyPrinting {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("snapshotRecordTime", _.snapshotRecordTime),
      param("migrationId", _.migrationId),
      param("lastSnapshot", _.lastSnapshot),
    )
  }

}
