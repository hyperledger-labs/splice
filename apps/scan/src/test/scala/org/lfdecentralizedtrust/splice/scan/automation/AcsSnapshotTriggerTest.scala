package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.Transaction
import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.environment.ledger.api.{TransactionTreeUpdate, TreeUpdate}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.AcsSnapshot
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
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import scala.concurrent.Future

class AcsSnapshotTriggerTest
    extends AnyWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  "AcsSnapshotTrigger" should {

    "when there's a previous snapshot" should {

      "do nothing if the next task is not yet due" in new AcsSnapshotTriggerTestScope(false) {
        previousSnapshot(now.minusSeconds(60L))

        trigger.retrieveTasks().futureValue should be(Seq.empty)
      }

      "do nothing if there might still be updates pending" in new AcsSnapshotTriggerTestScope(
        false
      ) {
        // The snapshot was taken more than an hour ago, so the next snapshot is due
        val lastSnapshotTime = now.minusSeconds(3700L)
        previousSnapshot(lastSnapshotTime)

        // but there's still updates pending
        when(
          updateHistory.getUpdatesWithoutImportUpdates(
            eqTo(Some((currentMigrationId, lastSnapshotTime.plusSeconds(3600L)))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(Future.successful(Seq.empty))

        trigger.retrieveTasks().futureValue should be(Seq.empty)
      }

      "return the new task when due and no updates pending" in new AcsSnapshotTriggerTestScope(
        false
      ) {
        // The snapshot was taken more than an hour ago, so the next snapshot is due
        val lastSnapshotTime = now.minusSeconds(3700L)
        val lastSnapshot = previousSnapshot(lastSnapshotTime)

        // there are no pending updates
        firstUpdateAt(
          currentMigrationId,
          queryRecordTime = lastSnapshotTime.plusSeconds(3600L),
          updateRecordTime = lastSnapshotTime.plusSeconds(3700L),
        )

        trigger.retrieveTasks().futureValue should be(
          Seq(
            AcsSnapshotTrigger.Task(
              lastSnapshotTime.plusSeconds(3600L),
              currentMigrationId,
              Some(lastSnapshot),
            )
          )
        )
      }

    }

    "when there's no previous snapshot" should {
      "do nothing if only an ACS import is present" in new AcsSnapshotTriggerTestScope(false) {
        noPreviousSnapshot()

        when(
          updateHistory.getUpdatesWithoutImportUpdates(
            eqTo(Some((currentMigrationId, CantonTimestamp.MinValue))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(Future.successful(Seq.empty))

        trigger.retrieveTasks().futureValue should be(Seq.empty)
      }

      "do nothing if there might still be updates pending" in new AcsSnapshotTriggerTestScope(
        false
      ) {
        noPreviousSnapshot()

        // data after ACS
        when(
          updateHistory.getUpdatesWithoutImportUpdates(
            eqTo(Some((currentMigrationId, CantonTimestamp.MinValue))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                UpdateHistoryResponse(treeUpdate(now.minusSeconds(1800L)), dummyDomain),
                1L,
              )
            )
          )
        )
        val firstSnapshotTime =
          CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-03T10:00:00.00Z"))

        // but there's still updates pending
        when(
          updateHistory.getUpdatesWithoutImportUpdates(
            eqTo(Some((currentMigrationId, firstSnapshotTime))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(Future.successful(Seq.empty))

        trigger.retrieveTasks().futureValue should be(Seq.empty)
      }

      "when not backfilling: return the first task when due and no updates pending" in new AcsSnapshotTriggerTestScope(
        false
      ) {
        noPreviousSnapshot()

        // data after ACS
        when(
          updateHistory.getUpdatesWithoutImportUpdates(
            eqTo(Some((currentMigrationId, CantonTimestamp.MinValue))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                UpdateHistoryResponse(treeUpdate(now.minusSeconds(1800L)), dummyDomain),
                1L,
              )
            )
          )
        )

        val firstSnapshotTime =
          CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-03T10:00:00.00Z"))

        // no updates pending
        when(
          updateHistory.getUpdatesWithoutImportUpdates(
            eqTo(Some((currentMigrationId, firstSnapshotTime))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                UpdateHistoryResponse(treeUpdate(now.plusSeconds(1800L)), dummyDomain),
                1L,
              )
            )
          )
        )

        trigger.retrieveTasks().futureValue should be(
          Seq(AcsSnapshotTrigger.Task(firstSnapshotTime, currentMigrationId, None))
        )
      }

      "when historyBackfilling=true" should {

        "when update history backfilling has not finished: return no task" in new AcsSnapshotTriggerTestScope(
          true
        ) {
          noPreviousSnapshot()

          // data after ACS
          when(
            updateHistory.getUpdatesWithoutImportUpdates(
              eqTo(Some((currentMigrationId, CantonTimestamp.MinValue))),
              eqTo(PageLimit.tryCreate(1)),
            )(any[TraceContext])
          ).thenReturn(
            Future.successful(
              Seq(
                TreeUpdateWithMigrationId(
                  UpdateHistoryResponse(treeUpdate(now.minusSeconds(1800L)), dummyDomain),
                  1L,
                )
              )
            )
          )

          historyBackfilled(currentMigrationId, complete = false)

          trigger.retrieveTasks().futureValue should be(empty)
        }

        "when update history backfilling has not finished import updates: return no task" in new AcsSnapshotTriggerTestScope(
          true
        ) {
          noPreviousSnapshot()

          // data after ACS
          when(
            updateHistory.getUpdatesWithoutImportUpdates(
              eqTo(Some((currentMigrationId, CantonTimestamp.MinValue))),
              eqTo(PageLimit.tryCreate(1)),
            )(any[TraceContext])
          ).thenReturn(
            Future.successful(
              Seq(
                TreeUpdateWithMigrationId(
                  UpdateHistoryResponse(treeUpdate(now.minusSeconds(1800L)), dummyDomain),
                  1L,
                )
              )
            )
          )

          historyPartiallyBackfilled(
            currentMigrationId,
            complete = true,
            importUpdatesComplete = false,
          )

          trigger.retrieveTasks().futureValue should be(empty)
        }

        // Currently, import updates are always backfilled after regular updates, but we do not want to depend on that in the trigger
        "when update history backfilling has not finished regular updates: return no task" in new AcsSnapshotTriggerTestScope(
          true
        ) {
          noPreviousSnapshot()

          // data after ACS
          when(
            updateHistory.getUpdatesWithoutImportUpdates(
              eqTo(Some((currentMigrationId, CantonTimestamp.MinValue))),
              eqTo(PageLimit.tryCreate(1)),
            )(any[TraceContext])
          ).thenReturn(
            Future.successful(
              Seq(
                TreeUpdateWithMigrationId(
                  UpdateHistoryResponse(treeUpdate(now.minusSeconds(1800L)), dummyDomain),
                  1L,
                )
              )
            )
          )

          historyPartiallyBackfilled(
            currentMigrationId,
            complete = false,
            importUpdatesComplete = true,
          )

          trigger.retrieveTasks().futureValue should be(empty)
        }

        // this is the case of when an SV joins late (and the history is backfilled),
        // or was present at the beginning of a migration (no backfilling required).
        "when update history backfilling has finished: return the first task when due and no updates pending" in new AcsSnapshotTriggerTestScope(
          true
        ) {
          noPreviousSnapshot()

          // data after ACS
          when(
            updateHistory.getUpdatesWithoutImportUpdates(
              eqTo(Some((currentMigrationId, CantonTimestamp.MinValue))),
              eqTo(PageLimit.tryCreate(1)),
            )(any[TraceContext])
          ).thenReturn(
            Future.successful(
              Seq(
                TreeUpdateWithMigrationId(
                  UpdateHistoryResponse(treeUpdate(now.minusSeconds(1800L)), dummyDomain),
                  1L,
                )
              )
            )
          )

          historyBackfilled(currentMigrationId, complete = true)

          val firstSnapshotTime =
            CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-03T10:00:00.00Z"))

          // no updates pending
          when(
            updateHistory.getUpdatesWithoutImportUpdates(
              eqTo(Some((currentMigrationId, firstSnapshotTime))),
              eqTo(PageLimit.tryCreate(1)),
            )(any[TraceContext])
          ).thenReturn(
            Future.successful(
              Seq(
                TreeUpdateWithMigrationId(
                  UpdateHistoryResponse(treeUpdate(now.plusSeconds(1800L)), dummyDomain),
                  1L,
                )
              )
            )
          )

          trigger.retrieveTasks().futureValue should be(
            Seq(AcsSnapshotTrigger.Task(firstSnapshotTime, currentMigrationId, None))
          )
        }

        "when the current migration id is complete" should {

          "return no task if the previous migration id is not backfilled" in new AcsSnapshotTriggerTestScope(
            true
          ) {
            // no snapshot needed for the current migration id
            historyBackfilled(currentMigrationId, complete = true)
            previousSnapshot(now.minusSeconds(60L), currentMigrationId)

            // therefore we attempt to backfill the previous migration id,
            // but that one is not yet backfilled
            historyBackfilled(currentMigrationId - 1, complete = false)

            trigger.retrieveTasks().futureValue should be(Seq.empty)
            trigger.isDoneBackfillingAcsSnapshots should be(false)
          }

          "return no task if the previous migration id is backfilled but didn't last long enough" in new AcsSnapshotTriggerTestScope(
            true,
            currentMigrationId = 1L,
          ) {
            // no snapshot needed for the current migration id
            historyBackfilled(currentMigrationId, complete = true)
            previousSnapshot(now.minusSeconds(60L), currentMigrationId)

            // therefore we attempt to backfill the previous migration id,
            // which is backfilled
            val previousMigrationId = currentMigrationId - 1
            historyBackfilled(previousMigrationId, complete = true)
            // last update happened shortly after the end
            recordTimeRange(
              previousMigrationId,
              // didn't last enough
              min = CantonTimestamp.MinValue.plusSeconds(3600L - 1),
              max = CantonTimestamp.MinValue.plusSeconds(3600L + 600),
            )
            noPreviousSnapshot(previousMigrationId)

            trigger.retrieveTasks().futureValue should be(Seq.empty)
            trigger.isDoneBackfillingAcsSnapshots should be(true)
          }

          "return the first task to backfill the previous migration id" in new AcsSnapshotTriggerTestScope(
            true
          ) {
            // no snapshot needed for the current migration id
            historyBackfilled(currentMigrationId, complete = true)
            previousSnapshot(now.minusSeconds(60L), currentMigrationId)

            // therefore we attempt to backfill the previous migration id,
            // which is backfilled
            val previousMigrationId = currentMigrationId - 1
            historyBackfilled(previousMigrationId, complete = true)
            // last update happened on 2007-11-03T10:15:30.00Z
            recordTimeRange(previousMigrationId, cantonTimestamp("2007-11-03T10:15:30.00Z"))
            noPreviousSnapshot(previousMigrationId)
            firstUpdateAt(
              previousMigrationId,
              queryRecordTime = CantonTimestamp.MinValue,
              // first update happened 10d ~3h before the last update
              updateRecordTime = cantonTimestamp("2007-01-03T07:55:30.00Z"),
            )

            trigger.retrieveTasks().futureValue should be(
              Seq(
                AcsSnapshotTrigger.Task(
                  // the next hour after 07:55:30.00Z, on the same day
                  cantonTimestamp("2007-01-03T08:00:00.00Z"),
                  currentMigrationId - 1,
                  None,
                )
              )
            )
            trigger.isDoneBackfillingAcsSnapshots should be(false)
          }

          "continue backfilling a previous migration id where it left off" in new AcsSnapshotTriggerTestScope(
            true
          ) {
            // no snapshot needed for the current migration id
            historyBackfilled(currentMigrationId, complete = true)
            previousSnapshot(now.minusSeconds(60L), currentMigrationId)

            // therefore we attempt to backfill the previous migration id,
            // which is backfilled
            val previousMigrationId = currentMigrationId - 1
            historyBackfilled(previousMigrationId, complete = true)
            // last update happened on 2007-11-03T10:15:30.00Z
            recordTimeRange(previousMigrationId, cantonTimestamp("2007-11-03T10:15:30.00Z"))
            // there was already a snapshot at 8
            val lastSnapshot =
              previousSnapshot(cantonTimestamp("2007-01-03T08:00:00.00Z"), previousMigrationId)

            trigger.retrieveTasks().futureValue should be(
              Seq(
                AcsSnapshotTrigger.Task(
                  // so the next one needs to happen at 9
                  cantonTimestamp("2007-01-03T09:00:00.00Z"),
                  currentMigrationId - 1,
                  Some(lastSnapshot),
                )
              )
            )
            trigger.isDoneBackfillingAcsSnapshots should be(false)
          }

          "once done with a migration id, it moves on to the next (previous) one" in new AcsSnapshotTriggerTestScope(
            true
          ) {
            // no snapshot needed for the current migration id
            historyBackfilled(currentMigrationId, complete = true)
            previousSnapshot(now.minusSeconds(60L), currentMigrationId)

            // the previous migration id history & snapshots are backfilled
            val previousMigrationId = currentMigrationId - 1
            historyBackfilled(previousMigrationId, complete = true)
            // last update happened on 2007-11-03T10:15:30.00Z
            recordTimeRange(previousMigrationId, cantonTimestamp("2007-11-03T10:15:30.00Z"))
            // the last possible snapshot for the migration id already exists,
            // note that the snapshot at T11 will not be done to avoid issues with recovery of a past migration id.
            previousSnapshot(cantonTimestamp("2007-11-03T10:00:00.00Z"), previousMigrationId)

            // so we move on to the previous-previous migration id
            historyBackfilled(previousMigrationId - 1, complete = true)
            // last update happened on 2007-11-24T19:25:30.00Z
            recordTimeRange(previousMigrationId - 1, cantonTimestamp("2007-11-24T19:25:30.00Z"))
            noPreviousSnapshot(previousMigrationId - 1)
            // first update at 2007-11-20T10:15:30.00Z
            firstUpdateAt(
              previousMigrationId - 1,
              queryRecordTime = CantonTimestamp.MinValue,
              updateRecordTime = cantonTimestamp("2007-11-20T10:15:30.00Z"),
            )

            trigger.retrieveTasks().futureValue should be(
              Seq(
                AcsSnapshotTrigger.Task(
                  cantonTimestamp("2007-11-20T11:00:00.00Z"),
                  // previous to previous
                  previousMigrationId - 1,
                  None,
                )
              )
            )
            trigger.isDoneBackfillingAcsSnapshots should be(false)
          }

          "stop backfilling once all previous migration ids have been backfilled" in new AcsSnapshotTriggerTestScope(
            true
          ) {
            // no snapshot needed for the current migration id
            historyBackfilled(currentMigrationId, complete = true)
            previousSnapshot(now.minusSeconds(60L), currentMigrationId)

            def migrationIdIsBackfilled(migrationId: Long) = {
              // the previous migration id history & snapshots are backfilled
              historyBackfilled(migrationId, complete = true)
              // last update happened on 2007-11-03T10:15:30.00Z
              recordTimeRange(migrationId, cantonTimestamp(s"200${migrationId}-11-03T10:15:30.00Z"))
              // the last possible snapshot for the migration id already exists
              previousSnapshot(
                cantonTimestamp(s"200${migrationId}-11-03T11:00:00.00Z"),
                migrationId,
              )
            }
            ((currentMigrationId - 1) to 0 by -1).foreach(migrationIdIsBackfilled)

            trigger.retrieveTasks().futureValue should be(Seq.empty)
            trigger.isDoneBackfillingAcsSnapshots should be(true)
          }

        }

      }

      "return the first task when due and no updates pending between 23:00 and 00:00" in new AcsSnapshotTriggerTestScope(
        false
      ) {
        override def now =
          CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-03T23:15:30.00Z"))
        noPreviousSnapshot()

        // data after ACS
        when(
          updateHistory.getUpdatesWithoutImportUpdates(
            eqTo(Some((currentMigrationId, CantonTimestamp.MinValue))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                UpdateHistoryResponse(treeUpdate(now.minusSeconds(1L)), dummyDomain),
                1L,
              )
            )
          )
        )

        val firstSnapshotTime =
          CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-04T00:00:00.00Z"))

        // no updates pending
        when(
          updateHistory.getUpdatesWithoutImportUpdates(
            eqTo(Some((currentMigrationId, firstSnapshotTime))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                UpdateHistoryResponse(treeUpdate(now.plusSeconds(1800L)), dummyDomain),
                1L,
              )
            )
          )
        )

        loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.INFO))(
          trigger.retrieveTasks().futureValue should be(Seq.empty),
          lines => {
            forExactly(1, lines) { line =>
              line.message should be(
                s"Still not time to take a snapshot. Now: $now. Next snapshot time: $firstSnapshotTime."
              )
            }
          },
        )
      }
    }

  }

  abstract class AcsSnapshotTriggerTestScope(
      updateHistoryBackfillEnabled: Boolean,
      val currentMigrationId: Long = 5L,
  ) {
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
      snapshotPeriodHours,
      updateHistoryBackfillEnabled = updateHistoryBackfillEnabled,
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

    def firstUpdateAt(
        migrationId: Long,
        queryRecordTime: CantonTimestamp,
        updateRecordTime: CantonTimestamp,
    ) = {
      when(
        updateHistory.getUpdatesWithoutImportUpdates(
          eqTo(Some((migrationId, queryRecordTime))),
          eqTo(PageLimit.tryCreate(1)),
        )(any[TraceContext])
      ).thenReturn(
        Future.successful(
          Seq(
            TreeUpdateWithMigrationId(
              UpdateHistoryResponse(
                treeUpdate(updateRecordTime),
                dummyDomain,
              ),
              migrationId,
            )
          )
        )
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
      when(updateHistory.getRecordTimeRange(eqTo(migrationId))(any[TraceContext]))
        .thenReturn(
          Future.successful(
            Map(dummyDomain -> DomainRecordTimeRange(min, max))
          )
        )
    }
  }

}
