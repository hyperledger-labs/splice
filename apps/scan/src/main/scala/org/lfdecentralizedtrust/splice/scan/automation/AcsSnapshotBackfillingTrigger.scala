// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.MetricsContext
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  IncrementalAcsSnapshot,
  IncrementalAcsSnapshotTable,
}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, UpdateHistory}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotBackfillingTrigger.RetrieveTaskForBackfillingMigrationResult
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTriggerBase.RetrieveTaskForMigrationResult
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.store.HistoryMetrics.AcsSnapshotsMetrics

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

  override val snapshotMetrics: AcsSnapshotsMetrics = new HistoryMetrics(context.metricsFactory)(
    MetricsContext.Empty
  ).AcsSnapshotsBackfilling

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var isDone: Boolean = false

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var earliestKnownBackfilledMigrationId: Long = store.currentMigrationId

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AcsSnapshotTriggerBase.Task]] = {
    if (isDone) {
      Future.successful(Seq.empty)
    } else if (!updateHistory.isReady) {
      logger.debug("Waiting for UpdateHistory to become ready.")
      Future.successful(Seq.empty)
    } else {
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = earliestKnownBackfilledMigrationId,
          isHistoryBackfilled = updateHistory.isHistoryBackfilled,
          getIncrementalSnapshot = () => getIncrementalSnapshot(),
          getLatestSnapshot = getLatestSnapshot,
          getMinRecordTime = getMinRecordTime,
          getMaxRecordTime = getMaxRecordTime,
          getPreviousMigrationId = getPreviousMigrationId,
          storageConfig = storageConfig,
          updateInterval = updateInterval,
          logger: TracedLogger,
        )
        .flatMap {
          case RetrieveTaskForBackfillingMigrationResult.Done =>
            logger.info(
              s"Backfilling incremental ACS snapshots is complete, this trigger won't do any work again."
            )
            isDone = true
            Future.successful(Seq.empty)
          case RetrieveTaskForBackfillingMigrationResult.Waiting(migrationId) =>
            earliestKnownBackfilledMigrationId = migrationId
            Future.successful(Seq.empty)
          case RetrieveTaskForBackfillingMigrationResult.Task(migrationId, task) =>
            earliestKnownBackfilledMigrationId = migrationId
            Future.successful(Seq(task))
        }
    }
  }

}

object AcsSnapshotBackfillingTrigger {
  sealed trait RetrieveTaskForBackfillingMigrationResult
  object RetrieveTaskForBackfillingMigrationResult {
    final case class Waiting(migrationId: Long) extends RetrieveTaskForBackfillingMigrationResult
    case object Done extends RetrieveTaskForBackfillingMigrationResult
    final case class Task(migrationId: Long, task: AcsSnapshotTriggerBase.Task)
        extends RetrieveTaskForBackfillingMigrationResult
  }

  def retrieveTaskForBackfillingMigration(
      earliestKnownBackfilledMigrationId: Long,
      isHistoryBackfilled: (Long) => Future[Boolean],
      getIncrementalSnapshot: () => Future[Option[IncrementalAcsSnapshot]],
      getLatestSnapshot: (Long) => Future[Option[AcsSnapshot]],
      getMinRecordTime: (Long) => Future[Option[CantonTimestamp]],
      getMaxRecordTime: (Long) => Future[Option[CantonTimestamp]],
      getPreviousMigrationId: (Long) => Future[Option[Long]],
      storageConfig: ScanStorageConfig,
      updateInterval: java.time.Duration,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[RetrieveTaskForBackfillingMigrationResult] = {
    def goForMigration(
        migrationId: Long
    ): Future[RetrieveTaskForBackfillingMigrationResult] = {
      AcsSnapshotTriggerBase
        .retrieveTaskForMigration(
          migrationId = migrationId,
          isHistoryBackfilled = isHistoryBackfilled,
          getIncrementalSnapshot = getIncrementalSnapshot,
          getLatestSnapshot = getLatestSnapshot,
          getMinRecordTime = getMinRecordTime,
          getMaxRecordTime = getMaxRecordTime,
          getLastIngestedRecordTime = _ => Some(CantonTimestamp.MaxValue),
          storageConfig = storageConfig,
          updateInterval = updateInterval,
          logger = logger,
        )
        .flatMap {
          case RetrieveTaskForMigrationResult.Task(task) =>
            Future.successful(RetrieveTaskForBackfillingMigrationResult.Task(migrationId, task))
          case RetrieveTaskForMigrationResult.ReachedMigrationEnd =>
            getPreviousMigrationId(migrationId).flatMap {
              case Some(previousMigrationId) =>
                logger.debug(
                  s"Migration $migrationId has completed backfilling, looking for tasks in previous migration $previousMigrationId."
                )
                goForMigration(previousMigrationId)
              case None =>
                logger.info(
                  s"Migration $migrationId has completed backfilling, and no previous migrations found. Backfilling is complete."
                )
                Future.successful(RetrieveTaskForBackfillingMigrationResult.Done)
            }
          case RetrieveTaskForMigrationResult.Waiting =>
            Future.successful(RetrieveTaskForBackfillingMigrationResult.Waiting(migrationId))
        }
    }

    goForMigration(earliestKnownBackfilledMigrationId)
  }
}
