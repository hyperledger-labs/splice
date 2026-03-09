// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  IncrementalAcsSnapshot,
  IncrementalAcsSnapshotTable,
}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, UpdateHistory}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTriggerBase.RetrieveTaskForMigrationResult
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.store.HistoryMetrics.AcsSnapshotsMetrics

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

  override val snapshotMetrics: AcsSnapshotsMetrics = new HistoryMetrics(context.metricsFactory)(
    MetricsContext.Empty
  ).AcsSnapshots

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AcsSnapshotTriggerBase.Task]] = {
    if (!updateHistory.isReady) {
      logger.debug("Waiting for UpdateHistory to become ready.")
      Future.successful(Seq.empty)
    } else {
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = store.currentMigrationId,
          isHistoryBackfilled = updateHistory.isHistoryBackfilled,
          getLastIngestedRecordTime = getLastIngestedRecordTime,
          getIncrementalSnapshot = () => getIncrementalSnapshot(),
          getLatestSnapshot = getLatestSnapshot,
          getMinRecordTime = getMinRecordTime,
          storageConfig = storageConfig,
          updateInterval = updateInterval,
          logger = logger,
        )
    }
  }

}

object AcsSnapshotTrigger {

  def retrieveTaskForCurrentMigration(
      migrationId: Long,
      isHistoryBackfilled: (Long) => Future[Boolean],
      getLastIngestedRecordTime: (Long) => Option[CantonTimestamp],
      getIncrementalSnapshot: () => Future[Option[IncrementalAcsSnapshot]],
      getLatestSnapshot: (Long) => Future[Option[AcsSnapshot]],
      getMinRecordTime: (Long) => Future[Option[CantonTimestamp]],
      storageConfig: ScanStorageConfig,
      updateInterval: java.time.Duration,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[AcsSnapshotTriggerBase.Task]] = {
    AcsSnapshotTriggerBase
      .retrieveTaskForMigration(
        migrationId = migrationId,
        isHistoryBackfilled = isHistoryBackfilled,
        getIncrementalSnapshot = getIncrementalSnapshot,
        getLatestSnapshot = getLatestSnapshot,
        getMinRecordTime = getMinRecordTime,
        getMaxRecordTime = _ => Future.successful(Some(CantonTimestamp.MaxValue)),
        getLastIngestedRecordTime = getLastIngestedRecordTime,
        storageConfig = storageConfig,
        updateInterval = updateInterval,
        logger = logger,
      )
      .map {
        case RetrieveTaskForMigrationResult.Task(task) => Seq(task)
        case RetrieveTaskForMigrationResult.ReachedMigrationEnd => Seq.empty
        case RetrieveTaskForMigrationResult.Waiting => Seq.empty
      }
  }
}
