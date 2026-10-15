// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.testing.InMemoryMetricsFactory
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.bulk.{
  AcsSnapshotBulkStoragePersistentProgress,
  BulkStorage,
  UpdateHistoryBulkStoragePersistentProgress,
  UpdatesSegment,
}
import org.lfdecentralizedtrust.splice.scan.store.{ScanKeyValueProvider, ScanKeyValueStore}
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.store.{
  HistoryMetrics,
  StoreTestBase,
  TimestampWithMigrationId,
}

import java.time.Instant
import scala.concurrent.Future

class KvBackfillingProgressTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  private def ts(day: Int): CantonTimestamp =
    CantonTimestamp.tryFromInstant(Instant.parse(f"2026-01-$day%02dT00:00:00Z"))

  private def mkProgress: Future[KvBackfillingProgress] =
    ScanKeyValueStore(
      dsoParty = dsoParty,
      participantId = mkParticipantId("participant"),
      storage,
      loggerFactory,
    ).map { kvStore =>
      val kvProvider = new ScanKeyValueProvider(kvStore, loggerFactory)
      val metrics = new HistoryMetrics(new InMemoryMetricsFactory)(MetricsContext.Empty)
      new KvBackfillingProgress(
        new UpdateHistoryBulkStoragePersistentProgress(
          BulkStorage.updatesStagingKvStoreKey,
          kvProvider,
          metrics.BulkStorage.latestUpdatesSegmentStaging,
          loggerFactory,
        ),
        new AcsSnapshotBulkStoragePersistentProgress(
          BulkStorage.acsStagingKvStoreKey,
          BulkStorage.firstAcsSnapshotTimestampKvStoreKey,
          kvProvider,
          metrics.BulkStorage.latestAcsSnapshotStaging,
          loggerFactory,
        ),
        kvProvider,
        loggerFactory,
      )
    }

  "KvBackfillingProgress" should {
    "start with empty cursors and an incomplete marker" in {
      for {
        progress <- mkProgress
        updates <- progress.readUpdatesCursor
        snapshots <- progress.readSnapshotsCursor
        complete <- progress.isComplete
      } yield {
        updates shouldBe None
        snapshots shouldBe None
        complete shouldBe false
      }
    }

    "persist and read back both cursors" in {
      val segment = UpdatesSegment(
        TimestampWithMigrationId(ts(1), 0L),
        TimestampWithMigrationId(ts(2), 0L),
      )
      val snapshot = TimestampWithMigrationId(ts(2), 0L)
      for {
        progress <- mkProgress
        _ <- progress.persistUpdatesCursor(segment)
        _ <- progress.persistSnapshotsCursor(snapshot)
        updates <- progress.readUpdatesCursor
        snapshots <- progress.readSnapshotsCursor
      } yield {
        updates shouldBe Some(segment)
        snapshots shouldBe Some(snapshot)
      }
    }

    "mark completion once and reset it" in {
      for {
        progress <- mkProgress
        _ <- progress.markComplete()
        afterMark <- progress.isComplete
        _ <- progress.markComplete()
        afterSecondMark <- progress.isComplete
        _ <- progress.resetCompletion()
        afterReset <- progress.isComplete
      } yield {
        afterMark shouldBe true
        afterSecondMark shouldBe true
        afterReset shouldBe false
      }
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
