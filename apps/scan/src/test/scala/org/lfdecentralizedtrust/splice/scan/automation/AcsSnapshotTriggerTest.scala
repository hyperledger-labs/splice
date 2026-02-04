package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.Transaction
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.environment.ledger.api.{TransactionTreeUpdate, TreeUpdate}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  IncrementalAcsSnapshot,
  IncrementalAcsSnapshotTable,
}
import org.lfdecentralizedtrust.splice.store.{
  HistoryBackfilling,
  PageLimit,
  TreeUpdateWithMigrationId,
  UpdateHistory,
}
import UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.util.DomainRecordTimeRange
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTriggerBase.{
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
    "initialization" should {

      "initialize from an existing snapshot" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        val snapshot = previousSnapshot(now.minusSeconds(60L))

        trigger.retrieveTasks().futureValue should be(
          Seq(
            InitializeIncrementalSnapshotTask(
              from = snapshot,
              nextAt = snapshot.snapshotRecordTime.plusSeconds(3600L),
            )
          )
        )
      }

      "don't do anything if there are no updates" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        noPreviousSnapshot()
        noUpdates(currentMigrationId)

        trigger.retrieveTasks().futureValue shouldBe empty
      }

      "initialize from import updates if there is no existing snapshot" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        noPreviousSnapshot()
        updatesBetween(currentMigrationId, now.minusSeconds(60L), now.minusSeconds(1L))

        // First incremental snapshot starting from just before the first update,
        // with a targetRecordTime of the next full hour.
        trigger.retrieveTasks().futureValue should be(
          Seq(
            InitializeIncrementalSnapshotFromImportUpdatesTask(
              recordTime = now.minusSeconds(61L),
              migration = currentMigrationId,
              nextAt = cantonTimestamp("2007-12-03T11:00:00.00Z"),
            )
          )
        )
      }

      "don't do anything if history is still backfilling" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        noPreviousSnapshot()
        updatesBetween(currentMigrationId, now.minusSeconds(60L), now.minusSeconds(1L))
        historyBackfilled(currentMigrationId, complete = false)

        trigger.retrieveTasks().futureValue shouldBe empty
      }

      "don't do anything if history is still backfilling regular updates" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        noPreviousSnapshot()
        updatesBetween(currentMigrationId, now.minusSeconds(60L), now.minusSeconds(1L))
        // Note: this won't happen in practice as import updates are always backfilled after regular updates,
        // but we don't want to depend on that in the trigger.
        historyPartiallyBackfilled(
          currentMigrationId,
          complete = false,
          importUpdatesComplete = true,
        )

        trigger.retrieveTasks().futureValue shouldBe empty
      }

      "don't do anything if history is still backfilling import updates" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        noPreviousSnapshot()
        updatesBetween(currentMigrationId, now.minusSeconds(60L), now.minusSeconds(1L))
        historyPartiallyBackfilled(
          currentMigrationId,
          complete = true,
          importUpdatesComplete = false,
        )

        trigger.retrieveTasks().futureValue shouldBe empty
      }

      "delete snapshot from previous migration" in new AcsSnapshotTriggerTestScope() {
        val snapshot = IncrementalAcsSnapshot(
          snapshotId = 1L,
          historyId = 1L,
          tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
          recordTime = now.minusSeconds(1800L),
          migrationId = currentMigrationId - 1L,
          targetRecordTime = now.plusSeconds(1800L),
        )
        previousIncrementalSnapshot(snapshot)

        // Incremental snapshot was from a previous migration, delete it.
        trigger.retrieveTasks().futureValue should be(
          Seq(
            AcsSnapshotTriggerBase.DeleteIncrementalSnapshotTask(
              snapshot = snapshot
            )
          )
        )
      }
    }

    "updating" should {
      "update an incremental snapshot that is not near its target time" in new AcsSnapshotTriggerTestScope() {
        val snapshot = IncrementalAcsSnapshot(
          snapshotId = 1L,
          historyId = 1L,
          tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
          recordTime = now.minusSeconds(2800L),
          migrationId = currentMigrationId,
          targetRecordTime = now.minusSeconds(1800L),
        )
        previousIncrementalSnapshot(snapshot)
        updatesBetween(currentMigrationId, now.minusSeconds(6000L), now.minusSeconds(1L))

        // Update the incremental snapshot with 30sec worth of data.
        // It should NOT update until the targetRecordTime, even if that is long overdue.
        trigger.retrieveTasks().futureValue should be(
          Seq(
            AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(
              snapshot = snapshot,
              updateUntil = now.minusSeconds(2770L),
            )
          )
        )
      }
      "update an incremental snapshot that is almost finished" in new AcsSnapshotTriggerTestScope() {
        val snapshot = IncrementalAcsSnapshot(
          snapshotId = 1L,
          historyId = 1L,
          tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
          recordTime = now.minusSeconds(1801L),
          migrationId = currentMigrationId,
          targetRecordTime = now.minusSeconds(1800L),
        )
        previousIncrementalSnapshot(snapshot)
        updatesBetween(currentMigrationId, now.minusSeconds(6000L), now.minusSeconds(1L))

        // Update the incremental snapshot with 1sec worth of data, until exactly the targetRecordTime.
        trigger.retrieveTasks().futureValue should be(
          Seq(
            AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(
              snapshot = snapshot,
              updateUntil = now.minusSeconds(1800L),
            )
          )
        )
      }

      "don't do anything if not enough time has passed since last update" in new AcsSnapshotTriggerTestScope() {
        val snapshot = IncrementalAcsSnapshot(
          snapshotId = 1L,
          historyId = 1L,
          tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
          recordTime = now.minusSeconds(1L),
          migrationId = currentMigrationId,
          targetRecordTime = now.plusSeconds(1800L),
        )
        previousIncrementalSnapshot(snapshot)
        updatesBetween(currentMigrationId, now.minusSeconds(6000L), now.minusSeconds(1L))

        // The incremental snapshot is just 1sec old, don't try to update it yet
        trigger.retrieveTasks().futureValue shouldBe empty
      }

      "don't do anything if updateHistory has not caught up" in new AcsSnapshotTriggerTestScope() {
        val snapshot = IncrementalAcsSnapshot(
          snapshotId = 1L,
          historyId = 1L,
          tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
          recordTime = now.minusSeconds(600L),
          migrationId = currentMigrationId,
          targetRecordTime = now.plusSeconds(1800L),
        )
        previousIncrementalSnapshot(snapshot)
        updatesBetween(currentMigrationId, now.minusSeconds(6000L), now.minusSeconds(600L))

        // The incremental snapshot is 600sec old, but update history has no data after that,
        // so we can't update it further.
        trigger.retrieveTasks().futureValue shouldBe empty
      }
    }

    "finalization" should {
      "save incremental snapshot" in new AcsSnapshotTriggerTestScope() {
        val snapshot = IncrementalAcsSnapshot(
          snapshotId = 1L,
          historyId = 1L,
          tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Next.tableName,
          recordTime = now.minusSeconds(1800L),
          migrationId = currentMigrationId,
          targetRecordTime = now.minusSeconds(1800L),
        )
        previousIncrementalSnapshot(snapshot)

        // Incremental snapshot has reached its targetRecordTime, so it should be finalized
        trigger.retrieveTasks().futureValue should be(
          Seq(
            AcsSnapshotTriggerBase.SaveIncrementalSnapshotTask(
              snapshot = snapshot,
              nextAt = now.plusSeconds(1800L),
            )
          )
        )
      }
    }
  }

  "AcsSnapshotBackfillingTrigger" should {
    "initialize" should {
      "start backfilling from an empty incremental snapshot" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        noPreviousSnapshot(currentMigrationId - 1L)
        updatesBetween(currentMigrationId - 1L, now.minusSeconds(4600L), now.minusSeconds(10L))

        // There is no backfilled data at all, start from an empty snapshot in the previous migration.
        backfillingTrigger.retrieveTasks().futureValue should be(
          Seq(
            InitializeIncrementalSnapshotFromImportUpdatesTask(
              recordTime = now.minusSeconds(4601L),
              migration = currentMigrationId - 1L,
              nextAt = cantonTimestamp("2007-12-03T09:00:00.00Z"),
            )
          )
        )
        // backfillingTrigger.currentBackfillingMigrationId shouldBe Some(currentMigrationId - 1L)
      }
      "continue backfilling from an existing snapshot" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        val snapshot = previousSnapshot(now.minusSeconds(7200L), currentMigrationId - 1L)
        updatesBetween(currentMigrationId - 1L, now.minusSeconds(8000L), now.minusSeconds(10L))

        // Backfilling was halfway done when we switched to incremental snapshots,
        // continue where we left off.
        backfillingTrigger.retrieveTasks().futureValue should be(
          Seq(
            InitializeIncrementalSnapshotTask(
              from = snapshot,
              nextAt = snapshot.snapshotRecordTime.plusSeconds(3600L),
            )
          )
        )
        // backfillingTrigger.currentBackfillingMigrationId shouldBe Some(currentMigrationId - 1L)
      }
      "skip migrations that are too short" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        noPreviousSnapshot(currentMigrationId - 1L)
        noPreviousSnapshot(currentMigrationId - 2L)

        // The previous migration was very short-lived
        updatesBetween(currentMigrationId - 1L, now.minusSeconds(1000L), now.minusSeconds(1001L))
        updatesBetween(currentMigrationId - 2L, now.minusSeconds(6000L), now.minusSeconds(1000L))

        // So we skip it and start backfilling from the migration before that.
        backfillingTrigger.retrieveTasks().futureValue shouldBe empty
        // backfillingTrigger.currentBackfillingMigrationId shouldBe Some(currentMigrationId - 2L)
        backfillingTrigger.retrieveTasks().futureValue should be(
          Seq(
            InitializeIncrementalSnapshotFromImportUpdatesTask(
              recordTime = now.minusSeconds(6001L),
              migration = currentMigrationId - 2L,
              nextAt = cantonTimestamp("2007-12-03T09:00:00.00Z"),
            )
          )
        )
      }
      "skip migrations that are finished" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()

        // The previous migration is already fully backfilled
        previousSnapshot(now.minusSeconds(1800L), currentMigrationId - 1L)
        updatesBetween(currentMigrationId - 1L, now.minusSeconds(2000L), now.minusSeconds(1000L))

        // The migration before that has no data yet
        noPreviousSnapshot(currentMigrationId - 2L)
        updatesBetween(currentMigrationId - 2L, now.minusSeconds(6000L), now.minusSeconds(2000L))

        // Skip the completed migration and start backfilling from the migration before that.
        backfillingTrigger.retrieveTasks().futureValue shouldBe empty
        // backfillingTrigger.currentBackfillingMigrationId shouldBe Some(currentMigrationId - 2L)
        backfillingTrigger.retrieveTasks().futureValue should be(
          Seq(
            InitializeIncrementalSnapshotFromImportUpdatesTask(
              recordTime = now.minusSeconds(6001L),
              migration = currentMigrationId - 2L,
              nextAt = cantonTimestamp("2007-12-03T09:00:00.00Z"),
            )
          )
        )
      }
      "don't do anything if update history backfilling has not completed" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()
        noPreviousSnapshot(currentMigrationId - 1L)

        updatesBetween(currentMigrationId - 1L, now.minusSeconds(4600L), now.minusSeconds(10L))
        historyBackfilled(currentMigrationId - 1L, complete = false)

        backfillingTrigger.retrieveTasks().futureValue shouldBe empty
      }
      "don't do anything when all migrations are complete" in new AcsSnapshotTriggerTestScope() {
        noPreviousIncrementalSnapshot()

        // All previous migrations are complete
        (0L to currentMigrationId - 1L).foreach { migrationId =>
          previousSnapshot(now.minusSeconds(1800L), migrationId)
          updatesBetween(migrationId, now.minusSeconds(2000L), now.minusSeconds(1000L))
        }

        // All migrations are complete, nothing to backfill.
        eventually() {
          backfillingTrigger.retrieveTasks().futureValue shouldBe empty
          backfillingTrigger.isDoneBackfillingAcsSnapshots shouldBe true
        }
      }
    }

    "update" should {
      "continue backfilling from an existing incremental snapshot" in new AcsSnapshotTriggerTestScope() {
        val snapshot = IncrementalAcsSnapshot(
          snapshotId = 1L,
          historyId = 1L,
          tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill.tableName,
          recordTime = now.minusSeconds(2800L),
          migrationId = currentMigrationId - 2L,
          targetRecordTime = now.minusSeconds(1800L),
        )
        previousIncrementalSnapshot(snapshot)
        updatesBetween(currentMigrationId - 2L, now.minusSeconds(6000L), now.minusSeconds(1L))

        // Update the incremental snapshot with 30sec worth of data.
        backfillingTrigger.retrieveTasks().futureValue should be(
          Seq(
            AcsSnapshotTriggerBase.UpdateIncrementalSnapshotTask(
              snapshot = snapshot,
              updateUntil = now.minusSeconds(2770L),
            )
          )
        )
        // backfillingTrigger.currentBackfillingMigrationId shouldBe Some(currentMigrationId - 2L)
      }
      "save snapshot at its target record time" in new AcsSnapshotTriggerTestScope() {
        val snapshot = IncrementalAcsSnapshot(
          snapshotId = 1L,
          historyId = 1L,
          tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill.tableName,
          recordTime = now.minusSeconds(1800L),
          migrationId = currentMigrationId - 2L,
          targetRecordTime = now.minusSeconds(1800L),
        )
        previousIncrementalSnapshot(snapshot)
        updatesBetween(currentMigrationId - 2L, now.minusSeconds(6000L), now.minusSeconds(1700L))

        // Incremental snapshot has reached its targetRecordTime, so it should be saved
        backfillingTrigger.retrieveTasks().futureValue should be(
          Seq(
            AcsSnapshotTriggerBase.SaveIncrementalSnapshotTask(
              snapshot = snapshot,
              nextAt = now.plusSeconds(1800L),
            )
          )
        )
        // backfillingTrigger.currentBackfillingMigrationId shouldBe Some(currentMigrationId - 2L)
      }
      "move to the next migration when done with one" in new AcsSnapshotTriggerTestScope() {
        val snapshot = IncrementalAcsSnapshot(
          snapshotId = 1L,
          historyId = 1L,
          tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill.tableName,
          recordTime = now.minusSeconds(1800L),
          migrationId = currentMigrationId - 2L,
          targetRecordTime = now.plusSeconds(1800L),
        )
        previousIncrementalSnapshot(snapshot)
        previousSnapshot(now.minusSeconds(1800L), currentMigrationId - 2L)
        updatesBetween(currentMigrationId - 2L, now.minusSeconds(6000L), now.minusSeconds(1700L))
        updatesBetween(currentMigrationId - 3L, now.minusSeconds(9900L), now.minusSeconds(6000L))

        // Incremental snapshot has a targetRecordTime beyond the end of the migration,
        // move on to the next migration
        backfillingTrigger.retrieveTasks().futureValue shouldBe empty
        // backfillingTrigger.currentBackfillingMigrationId shouldBe Some(currentMigrationId - 3L)
        backfillingTrigger.retrieveTasks().futureValue should be(
          Seq(
            AcsSnapshotTriggerBase.DeleteIncrementalSnapshotTask(
              snapshot = snapshot
            )
          )
        )
      }
    }
    "stop when all migrations are complete" in new AcsSnapshotTriggerTestScope() {
      // The incremental snapshot is at the end of the oldest migration
      val snapshot = IncrementalAcsSnapshot(
        snapshotId = 1L,
        historyId = 1L,
        tableName = AcsSnapshotStore.IncrementalAcsSnapshotTable.Backfill.tableName,
        recordTime = now.minusSeconds(1800L),
        migrationId = 0L,
        targetRecordTime = now.plusSeconds(1800L),
      )
      previousIncrementalSnapshot(snapshot)
      previousSnapshot(now.minusSeconds(1800L), 0L)
      updatesBetween(0L, now.minusSeconds(2000L), now.minusSeconds(1000L))

      // All migrations are complete, nothing to backfill.
      backfillingTrigger.retrieveTasks().futureValue shouldBe empty
      backfillingTrigger.isDoneBackfillingAcsSnapshots shouldBe true
    }
  }

  abstract class AcsSnapshotTriggerTestScope(
      val currentMigrationId: Long = 5L
  ) {
    final def storageConfig = ScanStorageConfig(
      dbAcsSnapshotPeriodHours = 1,
      1, // ignored in this test
      0, // ignored in this test
      0L, // ignored in this test
      0L, // ignored in this test
    )
    final def snapshotPeriodHours: Int = 1

    val clock = new SimClock(loggerFactory = loggerFactory)

    def cantonTimestamp(isoStr: String) =
      CantonTimestamp.assertFromInstant(java.time.Instant.parse(isoStr))
    def now = cantonTimestamp("2007-12-03T10:15:30.00Z")
    clock.advanceTo(now)

    val dummyDomain = SynchronizerId.tryFromString("dummy::domain")
    def treeUpdate(recordTime: CantonTimestamp): TreeUpdate = {
      TransactionTreeUpdate(
        new Transaction(
          "updateId",
          "commandId",
          "workflowId",
          recordTime.toInstant,
          java.util.Collections.emptyList(),
          0L,
          dummyDomain.toProtoPrimitive,
          TraceContextOuterClass.TraceContext.getDefaultInstance,
          recordTime.toInstant,
          ByteString.EMPTY,
        )
      )
    }

    val triggerContext: TriggerContext = TriggerContext(
      AutomationConfig(),
      clock,
      clock,
      TriggerEnabledSynchronization.Noop,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      loggerFactory,
      NoOpMetricsFactory,
    )
    val store: AcsSnapshotStore = mock[AcsSnapshotStore]
    val historyId: Long = 1L
    when(store.currentMigrationId).thenReturn(currentMigrationId)
    val updateHistory: UpdateHistory = mock[UpdateHistory]
    when(updateHistory.isReady).thenReturn(true)
    val sourceHistory = mock[HistoryBackfilling.SourceHistory[UpdateHistoryResponse]]
    when(updateHistory.isHistoryBackfilled(anyLong)(any[TraceContext]))
      .thenAnswer { (migrationId: Long) =>
        sourceHistory
          .migrationInfo(migrationId)
          .map(_.exists(i => i.complete && i.importUpdatesComplete))
      }
    // migrationInfo() is only used to check whether backfilling is complete
    when(sourceHistory.migrationInfo(anyLong)(any[TraceContext]))
      .thenReturn(
        Future.successful(
          Some(
            HistoryBackfilling.SourceMigrationInfo(
              previousMigrationId = None,
              recordTimeRange = Map.empty,
              lastImportUpdateId = None,
              complete = true,
              importUpdatesComplete = true,
            )
          )
        )
      )
    when(updateHistory.sourceHistory).thenReturn(sourceHistory)
    when(updateHistory.getPreviousMigrationId(anyLong)(any[TraceContext])).thenAnswer { (n: Long) =>
      Future.successful(n match {
        case 0L => None
        case n => Some(n - 1)
      })
    }
    val trigger = new AcsSnapshotTrigger(
      store,
      updateHistory,
      storageConfig,
      triggerContext,
    )
    val backfillingTrigger = new AcsSnapshotBackfillingTrigger(
      store,
      updateHistory,
      storageConfig,
      triggerContext,
    )

    def noPreviousSnapshot(migrationId: Long = currentMigrationId): Unit = {
      when(
        store.lookupSnapshotAtOrBefore(eqTo(migrationId), eqTo(CantonTimestamp.MaxValue))(
          any[TraceContext]
        )
      )
        .thenReturn(
          Future.successful(None)
        )
    }

    def previousSnapshot(
        time: CantonTimestamp,
        migrationId: Long = currentMigrationId,
    ): AcsSnapshot = {
      val lastSnapshot = AcsSnapshot(time, migrationId, historyId, 0, 100, None, None)
      when(
        store.lookupSnapshotAtOrBefore(eqTo(migrationId), eqTo(CantonTimestamp.MaxValue))(
          any[TraceContext]
        )
      )
        .thenReturn(
          Future.successful(Some(lastSnapshot))
        )
      lastSnapshot
    }

    def previousIncrementalSnapshot(
        snapshot: IncrementalAcsSnapshot
    ): Unit = {
      when(
        store.getIncrementalSnapshot(any[IncrementalAcsSnapshotTable])(
          any[TraceContext]
        )
      )
        .thenReturn(
          Future.successful(Some(snapshot))
        )
    }

    def noPreviousIncrementalSnapshot(
    ): Unit = {
      when(
        store.getIncrementalSnapshot(any[IncrementalAcsSnapshotTable])(
          any[TraceContext]
        )
      )
        .thenReturn(
          Future.successful(None)
        )
    }

    def updatesBetween(
        migrationId: Long,
        minRecordTime: CantonTimestamp,
        maxRecordTime: CantonTimestamp,
    ) = {
      when(
        updateHistory.getUpdatesWithoutImportUpdates(
          eqTo(Some((migrationId, CantonTimestamp.MinValue))),
          eqTo(PageLimit.tryCreate(1)),
        )(any[TraceContext])
      ).thenReturn(
        Future.successful(
          Seq(
            TreeUpdateWithMigrationId(
              UpdateHistoryResponse(
                treeUpdate(minRecordTime),
                dummyDomain,
              ),
              migrationId,
            )
          )
        )
      )
      when(
        updateHistory.getRecordTimeRangeBySynchronizer(eqTo(migrationId))(any[TraceContext])
      ).thenReturn(
        Future.successful(
          Map(dummyDomain -> DomainRecordTimeRange(minRecordTime, maxRecordTime))
        )
      )
      when(
        updateHistory.lastIngestedRecordTime
      ).thenReturn(
        Some(maxRecordTime)
      )
    }

    def noUpdates(migrationId: Long) = {
      when(
        updateHistory.getUpdatesWithoutImportUpdates(
          eqTo(Some((migrationId, CantonTimestamp.MinValue))),
          eqTo(PageLimit.tryCreate(1)),
        )(any[TraceContext])
      ).thenReturn(
        Future.successful(
          Seq.empty
        )
      )
      when(
        updateHistory.getRecordTimeRangeBySynchronizer(eqTo(migrationId))(any[TraceContext])
      ).thenReturn(
        Future.successful(
          Map.empty
        )
      )
      when(
        updateHistory.lastIngestedRecordTime
      ).thenReturn(
        None
      )
    }

    def historyBackfilled(migrationId: Long, complete: Boolean): Unit = {
      when(
        updateHistory.sourceHistory.migrationInfo(eqTo(migrationId))(any[TraceContext])
      )
        .thenReturn(
          Future.successful(
            Some(
              HistoryBackfilling.SourceMigrationInfo(
                previousMigrationId = None,
                recordTimeRange = Map.empty,
                lastImportUpdateId = None,
                complete = complete,
                importUpdatesComplete = complete,
              )
            )
          )
        )
    }

    def historyPartiallyBackfilled(
        migrationId: Long,
        complete: Boolean,
        importUpdatesComplete: Boolean,
    ): Unit = {
      when(
        updateHistory.sourceHistory.migrationInfo(eqTo(migrationId))(any[TraceContext])
      )
        .thenReturn(
          Future.successful(
            Some(
              HistoryBackfilling.SourceMigrationInfo(
                previousMigrationId = None,
                recordTimeRange = Map.empty,
                lastImportUpdateId = None,
                complete = complete,
                importUpdatesComplete = importUpdatesComplete,
              )
            )
          )
        )
    }

    def recordTimeRange(
        migrationId: Long,
        max: CantonTimestamp,
        min: CantonTimestamp = CantonTimestamp.MinValue,
    ): Unit = {
      when(updateHistory.getRecordTimeRangeBySynchronizer(eqTo(migrationId))(any[TraceContext]))
        .thenReturn(
          Future.successful(
            Map(dummyDomain -> DomainRecordTimeRange(min, max))
          )
        )
    }
  }

}
