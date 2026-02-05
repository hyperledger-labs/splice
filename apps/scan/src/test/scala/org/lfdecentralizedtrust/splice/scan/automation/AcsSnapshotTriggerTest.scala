package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  IncrementalAcsSnapshot,
}
import org.lfdecentralizedtrust.splice.util.DomainRecordTimeRange
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.Done
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTriggerBase.{
  DeleteIncrementalSnapshotTask,
  InitializeIncrementalSnapshotFromImportUpdatesTask,
  InitializeIncrementalSnapshotTask,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future

class AcsSnapshotTriggerTest
    extends AnyWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  "AcsSnapshotTrigger" should {

    "initialize from an existing snapshot" in {
      val previousSnapshot = snapshotAt(migration4, snapshotTime2)

      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = migration4,
          isHistoryBackfilled = returnForMigration(migration4 -> true),
          lastIngestedRecordTime = None,
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = returnForMigration(migration4 -> Some(previousSnapshot)),
          getRecordTimeRange = unused1,
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue
        .loneElement shouldBe
        InitializeIncrementalSnapshotTask(
          from = previousSnapshot,
          nextAt = snapshotTime3,
        )
    }

    "don't do anything if there are no updates" in {
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = migration4,
          isHistoryBackfilled = returnForMigration(migration4 -> true),
          lastIngestedRecordTime = None,
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = returnForMigration(migration4 -> None),
          getRecordTimeRange = returnForMigration(migration4 -> None),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe empty
    }

    "initialize from import updates if there is no existing snapshot" in {
      val migrationBegin = snapshotTime1.minusSeconds(100L)
      val recordTimeRange = DomainRecordTimeRange(migrationBegin, snapshotTime4)

      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = migration4,
          isHistoryBackfilled = returnForMigration(migration4 -> true),
          lastIngestedRecordTime = None,
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = returnForMigration(migration4 -> None),
          getRecordTimeRange = returnForMigration(migration4 -> Some(recordTimeRange)),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue
        .loneElement shouldBe
        InitializeIncrementalSnapshotFromImportUpdatesTask(
          recordTime = migrationBegin.minusSeconds(1L),
          migration = migration4,
          nextAt = snapshotTime1,
        )
    }

    "don't do anything if history is still backfilling" in {
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = migration4,
          isHistoryBackfilled = returnForMigration(migration4 -> false),
          lastIngestedRecordTime = None,
          getIncrementalSnapshot = unused0,
          getLatestSnapshot = unused1,
          getRecordTimeRange = unused1,
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe empty
    }

    "delete snapshot from previous migration" in {
      val incrementalSnapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
        recordTime = snapshotTime1,
        migrationId = migration3,
        targetRecordTime = snapshotTime2,
      )

      // Incremental snapshot was from a previous migration, delete it.
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = migration4,
          isHistoryBackfilled = returnForMigration(migration4 -> true),
          lastIngestedRecordTime = None,
          getIncrementalSnapshot = () => Future.successful(Some(incrementalSnapshot)),
          getLatestSnapshot = unused1,
          getRecordTimeRange = unused1,
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue
        .loneElement shouldBe
        AcsSnapshotTriggerBase.DeleteIncrementalSnapshotTask(
          snapshot = incrementalSnapshot
        )
    }

    "update an incremental snapshot that is not near its target time" in {
      val incrementalSnapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
        recordTime = snapshotTime1.plusSeconds(100L),
        migrationId = migration4,
        targetRecordTime = snapshotTime2,
      )
      val lastIngestedRecordTime = snapshotTime4

      // Update the incremental snapshot with 30sec worth of data.
      // It should NOT update until the targetRecordTime, even if that is long overdue.
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = migration4,
          isHistoryBackfilled = returnForMigration(migration4 -> true),
          lastIngestedRecordTime = Some(lastIngestedRecordTime),
          getIncrementalSnapshot = () => Future.successful(Some(incrementalSnapshot)),
          getLatestSnapshot = unused1,
          getRecordTimeRange = unused1,
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue
        .loneElement shouldBe
        AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(
          snapshot = incrementalSnapshot,
          updateUntil = incrementalSnapshot.recordTime.plusSeconds(30L),
        )
    }

    "update an incremental snapshot that is near its target time" in {
      val incrementalSnapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
        recordTime = snapshotTime2.minusSeconds(1L),
        migrationId = migration4,
        targetRecordTime = snapshotTime2,
      )
      val lastIngestedRecordTime = snapshotTime4

      // Incremental snapshot is within 1sec of its targetRecordTime, update it all the way to the target.
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = migration4,
          isHistoryBackfilled = returnForMigration(migration4 -> true),
          lastIngestedRecordTime = Some(lastIngestedRecordTime),
          getIncrementalSnapshot = () => Future.successful(Some(incrementalSnapshot)),
          getLatestSnapshot = unused1,
          getRecordTimeRange = unused1,
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue
        .loneElement shouldBe
        AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(
          snapshot = incrementalSnapshot,
          updateUntil = incrementalSnapshot.targetRecordTime,
        )
    }

    "don't advance incremental snapshot beyond last ingested record time" in {
      val incrementalSnapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
        recordTime = snapshotTime2.plusSeconds(100L),
        migrationId = migration4,
        targetRecordTime = snapshotTime3,
      )
      val lastIngestedRecordTime = incrementalSnapshot.recordTime.plusSeconds(1L)

      // Incremental snapshot is only 1sec behind last ingested record time.
      // Do NOT update it until last ingested record time, that would result in a busy loop of tiny updates.
      // Instead, wait until more data is ingested.
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = migration4,
          isHistoryBackfilled = returnForMigration(migration4 -> true),
          lastIngestedRecordTime = Some(lastIngestedRecordTime),
          getIncrementalSnapshot = () => Future.successful(Some(incrementalSnapshot)),
          getLatestSnapshot = unused1,
          getRecordTimeRange = unused1,
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe empty
    }

    "save incremental snapshot" in {
      val incrementalSnapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
        recordTime = snapshotTime1,
        migrationId = migration4,
        targetRecordTime = snapshotTime1,
      )
      val lastIngestedRecordTime = snapshotTime4

      // Incremental snapshot has reached its targetRecordTime, so it should be saved
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = migration4,
          isHistoryBackfilled = returnForMigration(migration4 -> true),
          lastIngestedRecordTime = Some(lastIngestedRecordTime),
          getIncrementalSnapshot = () => Future.successful(Some(incrementalSnapshot)),
          getLatestSnapshot = unused1,
          getRecordTimeRange = unused1,
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue
        .loneElement shouldBe
        AcsSnapshotTriggerBase.SaveIncrementalSnapshotTask(
          snapshot = incrementalSnapshot,
          nextAt = snapshotTime2,
        )
    }
  }

  "AcsSnapshotBackfillingTrigger" should {

    "start backfilling from an empty incremental snapshot" in {
      val migrationBegin = snapshotTime1.minusSeconds(100L)
      val recordTimeRange = DomainRecordTimeRange(migrationBegin, snapshotTime4)

      // There is no backfilled data at all, initialize from import updates in the previous migration.
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration2,
          isHistoryBackfilled = returnForMigration(migration1 -> true),
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = returnForMigration(migration1 -> None),
          getRecordTimeRange = returnForMigration(migration1 -> Some(recordTimeRange)),
          getPreviousMigrationId = returnForMigration(migration2 -> Some(migration1)),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Right(
        Some(
          InitializeIncrementalSnapshotFromImportUpdatesTask(
            recordTime = migrationBegin.minusSeconds(1L),
            migration = migration1,
            nextAt = snapshotTime1,
          )
        )
      )
    }

    "start backfilling from an existing snapshot" in {
      val snapshot = snapshotAt(migration1, snapshotTime2)
      val recordTimeRange = DomainRecordTimeRange(snapshotTime1, snapshotTime4)

      // Backfilling was halfway done when we switched to incremental snapshots,
      // continue where we left off.
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration2,
          isHistoryBackfilled = returnForMigration(migration1 -> true),
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = returnForMigration(migration1 -> Some(snapshot)),
          getRecordTimeRange = returnForMigration(migration1 -> Some(recordTimeRange)),
          getPreviousMigrationId = returnForMigration(migration2 -> Some(migration1)),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Right(
        Some(
          InitializeIncrementalSnapshotTask(
            from = snapshot,
            nextAt = snapshot.snapshotRecordTime.plusSeconds(3600L),
          )
        )
      )
    }

    "skip migrations that are too short" in {
      // A record time range that does not contain a single snapshot time.
      val recordTimeRange2 = DomainRecordTimeRange(
        snapshotTime1.plusSeconds(1L),
        snapshotTime2.minusSeconds(1L),
      )
      // A record time range that does contain a snapshot time (even though it's very short).
      val recordTimeRange1 = DomainRecordTimeRange(
        snapshotTime1.minusSeconds(1L),
        snapshotTime1.plusSeconds(1L),
      )

      // Migration 2 was too short, so skip it and start backfilling in migration 1.
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration3,
          isHistoryBackfilled = returnForMigration(
            migration2 -> true,
            migration1 -> true,
          ),
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = returnForMigration(
            migration2 -> None,
            migration1 -> None,
          ),
          getRecordTimeRange = returnForMigration(
            migration2 -> Some(recordTimeRange2),
            migration1 -> Some(recordTimeRange1),
          ),
          getPreviousMigrationId = returnForMigration(
            migration3 -> Some(migration2),
            migration2 -> Some(migration1),
          ),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Right(
        Some(
          InitializeIncrementalSnapshotFromImportUpdatesTask(
            recordTime = recordTimeRange1.min.minusSeconds(1L),
            migration = migration1,
            nextAt = snapshotTime1,
          )
        )
      )
    }

    "skip migrations that are finished" in {
      // A record time range where snapshotTime2 is the last snapshot time.
      val recordTimeRange2 = DomainRecordTimeRange(
        snapshotTime1.minusSeconds(1L),
        snapshotTime2.plusSeconds(1L),
      )
      val lastSnapshotInMigration2 = snapshotAt(migration2, snapshotTime2)

      val recordTimeRange1 = DomainRecordTimeRange(
        snapshotTime3,
        snapshotTime4.plusSeconds(1L),
      )

      // Migration 2 has all non-incremental snapshots, so skip it and start backfilling in migration 1.
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration3,
          isHistoryBackfilled = returnForMigration(
            migration2 -> true,
            migration1 -> true,
          ),
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = returnForMigration(
            migration2 -> Some(lastSnapshotInMigration2),
            migration1 -> None,
          ),
          getRecordTimeRange = returnForMigration(
            migration2 -> Some(recordTimeRange2),
            migration1 -> Some(recordTimeRange1),
          ),
          getPreviousMigrationId = returnForMigration(
            migration3 -> Some(migration2),
            migration2 -> Some(migration1),
          ),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Right(
        Some(
          InitializeIncrementalSnapshotFromImportUpdatesTask(
            recordTime = recordTimeRange1.min.minusSeconds(1L),
            migration = migration1,
            nextAt = snapshotTime4,
          )
        )
      )
    }

    "don't do anything if update history backfilling has not completed" in {
      val recordTimeRange1 = DomainRecordTimeRange(
        snapshotTime1,
        snapshotTime4,
      )
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration2,
          isHistoryBackfilled = returnForMigration(migration1 -> false),
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = unused1,
          getRecordTimeRange = returnForMigration(migration1 -> Some(recordTimeRange1)),
          getPreviousMigrationId = returnForMigration(migration2 -> Some(migration1)),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Right(None)
    }

    "complete backfilling if there is no previous migration" in {
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration1,
          isHistoryBackfilled = unused1,
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = unused1,
          getRecordTimeRange = unused1,
          getPreviousMigrationId = returnForMigration(migration1 -> None),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Left(Done)
    }

    "complete backfilling if all previous migration are done" in {
      // A record time range where snapshotTime2 is the last snapshot time.
      val recordTimeRange = DomainRecordTimeRange(
        snapshotTime1.minusSeconds(1L),
        snapshotTime2.plusSeconds(1L),
      )
      val lastSnapshotInMigration2 = snapshotAt(migration2, snapshotTime2)
      val lastSnapshotInMigration1 = snapshotAt(migration1, snapshotTime2)

      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration3,
          isHistoryBackfilled = returnForMigration(
            migration2 -> true,
            migration1 -> true,
          ),
          getIncrementalSnapshot = () => Future.successful(None),
          getLatestSnapshot = returnForMigration(
            migration2 -> Some(lastSnapshotInMigration2),
            migration1 -> Some(lastSnapshotInMigration1),
          ),
          getRecordTimeRange = returnForMigration(
            migration2 -> Some(recordTimeRange),
            migration1 -> Some(recordTimeRange),
          ),
          getPreviousMigrationId = returnForMigration(
            migration3 -> Some(migration2),
            migration2 -> Some(migration1),
            migration1 -> None,
          ),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Left(Done)
    }

    "continue backfilling from an existing incremental snapshot" in {
      // A snapshot with recordTime < targetRecordTime
      val incrementalSnapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill.tableName,
        recordTime = snapshotTime2.minusSeconds(100L),
        migrationId = migration1,
        targetRecordTime = snapshotTime2,
      )
      val recordTimeRange = DomainRecordTimeRange(snapshotTime1, snapshotTime3)

      // Update the incremental snapshot with 30sec worth of data.
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration3,
          isHistoryBackfilled = returnForMigration(migration1 -> true),
          getIncrementalSnapshot = () => Future.successful(Some(incrementalSnapshot)),
          getLatestSnapshot = unused1,
          getRecordTimeRange = returnForMigration(migration1 -> Some(recordTimeRange)),
          getPreviousMigrationId = unused1,
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Right(
        Some(
          AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(
            snapshot = incrementalSnapshot,
            updateUntil = incrementalSnapshot.recordTime.plusSeconds(30L),
          )
        )
      )
    }

    "save snapshot at its target record time" in {
      // A snapshot with recordTime == targetRecordTime
      val incrementalSnapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill.tableName,
        recordTime = snapshotTime2,
        migrationId = migration1,
        targetRecordTime = snapshotTime2,
      )
      val recordTimeRange = DomainRecordTimeRange(snapshotTime1, snapshotTime3)

      // Save incremental snapshot.
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration2,
          isHistoryBackfilled = returnForMigration(migration1 -> true),
          getIncrementalSnapshot = () => Future.successful(Some(incrementalSnapshot)),
          getLatestSnapshot = unused1,
          getRecordTimeRange = returnForMigration(migration1 -> Some(recordTimeRange)),
          getPreviousMigrationId = unused1,
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Right(
        Some(
          AcsSnapshotTriggerBase.SaveIncrementalSnapshotTask(
            snapshot = incrementalSnapshot,
            nextAt = snapshotTime3,
          )
        )
      )
    }

    "move to the next migration when done with one" in {
      // A snapshot at the end of the migration (the next one would be beyond the end of the record time range)
      val incrementalSnapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill.tableName,
        recordTime = snapshotTime2,
        migrationId = migration2,
        targetRecordTime = snapshotTime3,
      )
      val recordTimeRange2 = DomainRecordTimeRange(
        snapshotTime1,
        snapshotTime3.minusSeconds(1L),
      )

      // Incremental snapshot has a targetRecordTime beyond the end of the migration,
      // move on to the next migration. This will result in deleting the incremental snapshot,
      // so that the next iteration can initialize a fresh one from import updates.
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration3,
          isHistoryBackfilled = unused1,
          getIncrementalSnapshot = () => Future.successful(Some(incrementalSnapshot)),
          getLatestSnapshot = unused1,
          getRecordTimeRange = returnForMigration(migration2 -> Some(recordTimeRange2)),
          getPreviousMigrationId = returnForMigration(migration2 -> Some(migration1)),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Right(
        Some(
          AcsSnapshotTriggerBase.DeleteIncrementalSnapshotTask(
            snapshot = incrementalSnapshot
          )
        )
      )
    }

    "stop when all migrations are complete" in {
      // The incremental snapshot just arrived at the last snapshot time inside the record time range
      val incrementalSnapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill.tableName,
        recordTime = snapshotTime2,
        migrationId = migration1,
        targetRecordTime = snapshotTime3,
      )
      val recordTimeRange = DomainRecordTimeRange(
        snapshotTime1.minusSeconds(1L),
        snapshotTime2.plusSeconds(1L),
      )

      // All migrations are complete, nothing to backfill.
      // The trigger should delete the incremental snapshot,
      // and the next iteration should mark the backfilling as done.
      AcsSnapshotBackfillingTrigger
        .retrieveTaskForBackfillingMigration(
          earliestKnownBackfilledMigrationId = migration3,
          isHistoryBackfilled = returnForMigration(migration1 -> true),
          getIncrementalSnapshot = () => Future.successful(Some(incrementalSnapshot)),
          getLatestSnapshot = unused1,
          getRecordTimeRange = returnForMigration(migration1 -> Some(recordTimeRange)),
          getPreviousMigrationId = returnForMigration(migration1 -> None),
          storageConfig = storageConfig,
          updateInterval = java.time.Duration.ofSeconds(30L),
          logger = logger,
        )
        .futureValue shouldBe Right(
        Some(
          DeleteIncrementalSnapshotTask(snapshot = incrementalSnapshot)
        )
      )
    }
  }

  private def storageConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 1,
    0, // ignored in this test
    0L, // ignored in this test
  )

  private def unused0[T]: () => Future[T] = () => fail("This argument should not be used")
  private def unused1[T]: Long => Future[T] = _ => fail("This argument should not be used")

  private def returnForMigration[T](arg: (Long, T)): Long => Future[T] = {
    case m if m == arg._1 => Future.successful(arg._2)
    case m => fail(s"Should not be called for migration $m")
  }
  private def returnForMigration[T](arg1: (Long, T), arg2: (Long, T)): Long => Future[T] = {
    case m if m == arg1._1 => Future.successful(arg1._2)
    case m if m == arg2._1 => Future.successful(arg2._2)
    case m => fail(s"Should not be called for migration $m")
  }
  private def returnForMigration[T](
      arg1: (Long, T),
      arg2: (Long, T),
      arg3: (Long, T),
  ): Long => Future[T] = {
    case m if m == arg1._1 => Future.successful(arg1._2)
    case m if m == arg2._1 => Future.successful(arg2._2)
    case m if m == arg3._1 => Future.successful(arg3._2)
    case m => fail(s"Should not be called for migration $m")
  }

  private def historyId = 1L

  private def snapshotAt(migrationId: Long, time: CantonTimestamp) =
    AcsSnapshot(time, migrationId, historyId, 0, 100, None, None)

  private def cantonTimestamp(isoStr: String) =
    CantonTimestamp.assertFromInstant(java.time.Instant.parse(isoStr))

  private def snapshotTime1 = cantonTimestamp("2007-12-03T01:00:00.00Z")
  private def snapshotTime2 = cantonTimestamp("2007-12-03T02:00:00.00Z")
  private def snapshotTime3 = cantonTimestamp("2007-12-03T03:00:00.00Z")
  private def snapshotTime4 = cantonTimestamp("2007-12-03T04:00:00.00Z")

  private def migration1 = 1L
  private def migration2 = 2L
  private def migration3 = 3L
  private def migration4 = 4L
}
