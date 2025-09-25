package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.StoreTest
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import io.circe.Json

import scala.concurrent.Future

class ScanEventStoreTest extends StoreTest with HasExecutionContext with SplicePostgresTest {

  "ScanEventStore" should {
    "combine verdict and update events by update_id" in {
      for {
        ctx <- newEventStore()
        recordTs = CantonTimestamp.now()
        tx <- insertUpdate(ctx.updateHistory, recordTs, "update1")
        updateId = tx.getUpdateId

        _ <- insertVerdict(ctx.verdictStore, updateId, recordTs)

        eventOpt <- ctx.eventStore.getEventByUpdateId(updateId, domainMigrationId)
        histUpdateOpt <- ctx.updateHistory.getUpdate(updateId)
      } yield {
        val (verdictOpt, updateOpt) = eventOpt.value
        verdictOpt.value._2.size shouldBe 1
        val view = verdictOpt.value._2.head
        view.verdictRowId shouldBe verdictOpt.value._1.rowId
        verdictOpt.value._1.recordTime shouldBe updateOpt.value.update.update.recordTime
        // Confirm the update returned by ScanEventStore matches UpdateHistory.getUpdate
        updateOpt.map(_.update.update) shouldBe histUpdateOpt.map(_.update.update)
      }
    }

    "Cap the updates till the latest verdict" in {
      for {
        ctx <- newEventStore()
        // First update
        recordTs1 = CantonTimestamp.now()
        tx1 <- insertUpdate(ctx.updateHistory, recordTs1, "update1")
        updateId1 = tx1.getUpdateId
        _ <- insertVerdict(ctx.verdictStore, updateId1, recordTs1)

        // Second update (with no verdict).
        recordTs2 = recordTs1.plusSeconds(1)
        tx2 <- insertUpdate(ctx.updateHistory, recordTs2, "update2")
        updateId2 = tx2.getUpdateId

        // Fetch without cursor
        events1 <- fetchEvents(ctx.eventStore, None, domainMigrationId, pageLimit)
        // Fetch with cursor
        events2 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          pageLimit,
        )
        // Fetch after recordTs1
        events3 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          pageLimit,
        )
        updateEventOpt <- ctx.eventStore.getEventByUpdateId(updateId2, domainMigrationId)
      } yield {
        // Expect only the first update (with a verdict) to appear for the current migration
        hasVerdict(events1, updateId1) shouldBe true

        // Ensure the second, later update without a verdict is filtered out
        hasUpdate(events1, updateId2) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true

        // Fetching by id should be filtered as well
        updateEventOpt.isEmpty shouldBe true
      }
    }

    "Cap the verdict till the latest update" in {
      for {
        ctx <- newEventStore()
        // First update
        recordTs1 = CantonTimestamp.now()
        tx1 <- insertUpdate(ctx.updateHistory, recordTs1, "update1")
        updateId1 = tx1.getUpdateId
        _ <- insertVerdict(ctx.verdictStore, updateId1, recordTs1)

        // Second verdict (with no update).
        recordTs2 = recordTs1.plusSeconds(1)
        updateId2 = "verdict-later-update-id"
        _ <- insertVerdict(ctx.verdictStore, updateId2, recordTs2)

        // Fetch without cursor
        events1 <- fetchEvents(ctx.eventStore, None, domainMigrationId, pageLimit)
        // Fetch with cursor
        events2 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          pageLimit,
        )
        // Fetch after recordTs1
        events3 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          pageLimit,
        )
        updateEventOpt <- ctx.eventStore.getEventByUpdateId(updateId2, domainMigrationId)
      } yield {
        // Expect only the first update (with a verdict) to appear for the current migration
        hasVerdict(events1, updateId1) shouldBe true

        // Ensure the second, later verdict without an update is filtered out
        hasUpdate(events1, updateId2) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true

        // Fetching by id should be filtered as well
        updateEventOpt.isEmpty shouldBe true
      }
    }

    "does not filter out the update matching the last verdict" in {
      for {
        ctx <- newEventStore()
        recordTs1 = CantonTimestamp.now()
        recordTs2 = recordTs1.plusSeconds(1)
        recordTs3 = recordTs2.plusSeconds(1)

        // Insert two verdicts at earlier times (no matching update ids)
        _ <- insertVerdict(ctx.verdictStore, "verdict-update-1", recordTs1)
        _ <- insertVerdict(ctx.verdictStore, "verdict-update-2", recordTs2)

        // Ingest an update and verdict at the latest time
        tx <- insertUpdate(ctx.updateHistory, recordTs3, "update3")
        updateId = tx.getUpdateId
        _ <- insertVerdict(ctx.verdictStore, updateId, recordTs3)

        // Fetch without cursor
        events1 <- fetchEvents(ctx.eventStore, None, domainMigrationId, pageLimit)
        // Fetch with cursor
        events2 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          pageLimit,
        )
        // Fetch after recordTs1
        events3 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          pageLimit,
        )
      } yield {
        events1.size shouldBe 3
        // All returned events should contain verdicts
        events1.forall(_._1.isDefined) shouldBe true
        // Ensure both verdict updateIds are present

        events1 shouldBe events2

        events3.size shouldBe 2
        events3.forall(_._1.isDefined) shouldBe true
        events3.exists(_._1.exists(_._1.updateId == "verdict-update-1")) shouldBe false
      }
    }

    "provide events for two migrationIds" in {
      val mig0 = domainMigrationId
      val mig1 = nextDomainMigrationId
      for {
        // Create two stores using the same underlying storage
        ctx0 <- newEventStore(mig0)
        ctx1 <- newEventStore(mig1)

        // Insert an update+verdict in migration 0
        recordTs1 = CantonTimestamp.now()
        tx1 <- insertUpdate(ctx0.updateHistory, recordTs1, "update-mig0")
        updateId1 = tx1.getUpdateId
        _ <- insertVerdict(ctx0.verdictStore, updateId1, recordTs1, migrationId = mig0)

        // Insert an update+verdict in migration 1
        recordTs2 = recordTs1.plusSeconds(1)
        tx2 <- insertUpdate(ctx1.updateHistory, recordTs2, "update-mig1")
        updateId2 = tx2.getUpdateId
        _ <- insertVerdict(ctx1.verdictStore, updateId2, recordTs2, migrationId = mig1)

        // Query combined events at current migration = mig1
        events <- fetchEvents(ctx1.eventStore, None, mig1, pageLimit)
        events2 <- fetchEvents(
          ctx1.eventStore,
          Some((mig0, recordTs1.minusSeconds(1))),
          mig1,
          pageLimit,
        )
        // after recordTs1
        events3 <- fetchEvents(ctx1.eventStore, Some((mig0, recordTs1)), mig1, pageLimit)
        // Fetch by id works across migrationIds
        e1 <- ctx1.eventStore.getEventByUpdateId(updateId1, domainMigrationId)
        e2 <- ctx1.eventStore.getEventByUpdateId(updateId2, domainMigrationId)
      } yield {
        // Cursor before ts1 should match no-cursor results
        events shouldBe events2

        // Should contain both updateIds
        hasUpdate(events, updateId1) shouldBe true
        hasUpdate(events, updateId2) shouldBe true

        // Cursor at ts1 for mig0 should exclude mig0 event and include only mig1
        events3.size shouldBe 1
        hasUpdate(events3, updateId1) shouldBe false
        hasUpdate(events3, updateId2) shouldBe true

        // Fetch by id should yield both verdict and update
        val (v1, u1) = e1.value
        val (v2, u2) = e2.value

        v1.value._1.migrationId shouldBe mig0
        v2.value._1.migrationId shouldBe mig1

        u1.value.migrationId shouldBe mig0
        u2.value.migrationId shouldBe mig1
      }
    }

    "provide assignment updates" in {
      for {
        ctx <- newEventStore()
        recordTs = CantonTimestamp.now()

        // Create an assignment (source -> target)
        reassignment <- insertAssign(ctx.updateHistory, recordTs, "assign-cid")

        recordTs2 = recordTs.plusSeconds(1)
        _ <- insertVerdict(ctx.verdictStore, "verdict-1", recordTs2)

        events <- fetchEvents(ctx.eventStore, None, domainMigrationId, pageLimit)
        histUpdates <- ctx.updateHistory.getUpdatesWithoutImportUpdates(None, pageLimit)

      } yield {
        hasUpdate(events, reassignment.updateId) shouldBe true

        // Confirm the update returned by ScanEventStore matches UpdateHistory.getUpdatesWithoutImportUpdates
        val eventUpdateOpt =
          events.flatMap(_._2).find(_.update.update.updateId == reassignment.updateId)
        val histUpdateOpt =
          histUpdates.find(_.update.update.updateId == reassignment.updateId)
        eventUpdateOpt.map(_.update.update) shouldBe histUpdateOpt.map(_.update.update)
      }
    }

    "provide unassignment updates" in {
      for {
        ctx <- newEventStore()
        recordTs = CantonTimestamp.now()

        // Create an unassignment (source -> target)
        reassignment <- insertUnassign(ctx.updateHistory, recordTs, "unassign-cid")

        recordTs2 = recordTs.plusSeconds(1)
        _ <- insertVerdict(ctx.verdictStore, "verdict-1", recordTs2)

        events <- fetchEvents(ctx.eventStore, None, domainMigrationId, pageLimit)
        histUpdates <- ctx.updateHistory.getUpdatesWithoutImportUpdates(None, pageLimit)
      } yield {
        hasUpdate(events, reassignment.updateId) shouldBe true

        // Confirm the update returned by ScanEventStore matches UpdateHistory.getUpdatesWithoutImportUpdates
        val eventUpdateOpt =
          events.flatMap(_._2).find(_.update.update.updateId == reassignment.updateId)
        val histUpdateOpt =
          histUpdates.find(_.update.update.updateId == reassignment.updateId)
        eventUpdateOpt.map(_.update.update) shouldBe histUpdateOpt.map(_.update.update)
      }
    }

    "Cap the assignments till the latest verdict" in {
      for {
        ctx <- newEventStore()
        // First assignment
        recordTs1 = CantonTimestamp.now()
        assignment1 <- insertAssign(ctx.updateHistory, recordTs1, "assign-cap-1")

        recordTs2 = recordTs1.plusSeconds(1)
        _ <- insertVerdict(ctx.verdictStore, "verdict-1", recordTs2)

        // Second assignment, after last verdict
        recordTs3 = recordTs2.plusSeconds(1)
        assignment2 <- insertAssign(ctx.updateHistory, recordTs3, "assign-cap-2")

        // Fetch without cursor
        events1 <- fetchEvents(ctx.eventStore, None, domainMigrationId, pageLimit)
        // Fetch with cursor
        events2 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          pageLimit,
        )
        // Fetch after latest verdict
        events3 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs2)),
          domainMigrationId,
          pageLimit,
        )
      } yield {
        // Only first assignment appears
        hasVerdict(events1, "verdict-1") shouldBe true

        // Later assignment is filtered
        hasUpdate(events1, assignment2.updateId) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true
      }
    }

    "Cap the verdict till the latest assignment" in {
      for {
        ctx <- newEventStore()
        // First verdict
        recordTs1 = CantonTimestamp.now()
        updateId1 = "verdict-before-assignment-id"
        _ <- insertVerdict(ctx.verdictStore, updateId1, recordTs1)

        recordTs2 = recordTs1.plusSeconds(1)
        assignment1 <- insertAssign(ctx.updateHistory, recordTs2, "assign-cap-3")

        // Second verdict
        recordTs3 = recordTs2.plusSeconds(1)
        updateId2 = "verdict-later-assignment-id"
        _ <- insertVerdict(ctx.verdictStore, updateId2, recordTs3)

        // Fetch without cursor
        events1 <- fetchEvents(ctx.eventStore, None, domainMigrationId, pageLimit)
        // Fetch with cursor
        events2 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          pageLimit,
        )
        // Fetch after latest assignment
        events3 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs2)),
          domainMigrationId,
          pageLimit,
        )
        updateEventOpt <- ctx.eventStore.getEventByUpdateId(updateId2, domainMigrationId)
      } yield {
        hasUpdate(events1, assignment1.updateId) shouldBe true
        hasVerdict(events1, updateId2) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true

        // Fetching by id should be filtered as well
        updateEventOpt.isEmpty shouldBe true
      }
    }

    "Cap the unassignments till the latest verdict" in {
      for {
        ctx <- newEventStore()
        // First unassignment
        recordTs1 = CantonTimestamp.now()
        unassignment1 <- insertUnassign(ctx.updateHistory, recordTs1, "unassign-cap-1")

        recordTs2 = recordTs1.plusSeconds(1)
        _ <- insertVerdict(ctx.verdictStore, "verdict-1", recordTs2)

        // Second unassignment, after last verdict
        recordTs3 = recordTs2.plusSeconds(1)
        unassignment2 <- insertUnassign(ctx.updateHistory, recordTs3, "unassign-cap-2")

        // Fetch without cursor
        events1 <- fetchEvents(ctx.eventStore, None, domainMigrationId, pageLimit)
        // Fetch with cursor
        events2 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          pageLimit,
        )
        // Fetch after latest verdict
        events3 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs2)),
          domainMigrationId,
          pageLimit,
        )
      } yield {
        // Only first unassignment appears
        hasVerdict(events1, "verdict-1") shouldBe true

        // Later unassignment is filtered
        hasUpdate(events1, unassignment2.updateId) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true

      }
    }

    "Cap the verdict till the latest unassignment" in {
      for {
        ctx <- newEventStore()
        // First verdict
        recordTs1 = CantonTimestamp.now()
        updateId1 = "verdict-before-unassignment-id"
        _ <- insertVerdict(ctx.verdictStore, updateId1, recordTs1)

        recordTs2 = recordTs1.plusSeconds(1)
        unassignment1 <- insertUnassign(ctx.updateHistory, recordTs2, "unassign-cap-3")

        // Second verdict
        recordTs3 = recordTs2.plusSeconds(1)
        updateId2 = "verdict-later-unassignment-id"
        _ <- insertVerdict(ctx.verdictStore, updateId2, recordTs3)

        // Fetch without cursor
        events1 <- fetchEvents(ctx.eventStore, None, domainMigrationId, pageLimit)
        // Fetch with cursor
        events2 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          pageLimit,
        )
        // Fetch after latest unassignment
        events3 <- fetchEvents(
          ctx.eventStore,
          Some((domainMigrationId, recordTs2)),
          domainMigrationId,
          pageLimit,
        )
        updateEventOpt <- ctx.eventStore.getEventByUpdateId(updateId2, domainMigrationId)
      } yield {
        hasUpdate(events1, unassignment1.updateId) shouldBe true
        hasVerdict(events1, updateId2) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true

        // Fetching by id should be filtered as well
        updateEventOpt.isEmpty shouldBe true
      }
    }

    "does not cap the updates for migrationId < currentMigrationId" in {
      val mig0 = domainMigrationId
      val mig1 = mig0 + 1
      val mig2 = mig1 + 1
      for {
        ctx0 <- newEventStore(mig0)
        ctx1 <- newEventStore(mig1)
        ctx2 <- newEventStore(mig2)

        // mig0: has only one update (T1)
        recordTs1 = CantonTimestamp.now()
        tx0 <- insertUpdate(ctx0.updateHistory, recordTs1, "update-mig0")
        updateId0 = tx0.getUpdateId

        // mig1: has 1 verdict (T2), then one update (T3)
        recordTs2 = recordTs1.plusSeconds(1)
        _ <- insertVerdict(ctx1.verdictStore, "verdict-mig1", recordTs2, migrationId = mig1)
        recordTs3 = recordTs2.plusSeconds(1)
        tx1 <- insertUpdate(ctx1.updateHistory, recordTs3, "update-mig1")
        updateId1 = tx1.getUpdateId

        // mig2 (current): has 1 verdict (T4), then one update (T5)
        recordTs4 = recordTs3.plusSeconds(1)
        _ <- insertVerdict(ctx2.verdictStore, "verdict-mig2", recordTs4, migrationId = mig2)
        recordTs5 = recordTs4.plusSeconds(1)
        tx2 <- insertUpdate(ctx2.updateHistory, recordTs5, "update-mig2")
        updateId2 = tx2.getUpdateId

        // Query combined events at current migration = mig2
        events <- fetchEvents(ctx2.eventStore, None, mig2, pageLimit)
      } yield {
        // mig0 + mig1 updates should be present; mig2 update should be filtered (after cap)
        hasUpdate(events, updateId0) shouldBe true
        hasUpdate(events, updateId1) shouldBe true
        hasUpdate(events, updateId2) shouldBe false
        hasVerdict(events, "verdict-mig1") shouldBe true
      }
    }

    "does not cap the verdicts for migrationId < currentMigrationId" in {
      val mig0 = domainMigrationId
      val mig1 = mig0 + 1
      val mig2 = mig1 + 1
      for {
        ctx0 <- newEventStore(mig0)
        ctx1 <- newEventStore(mig1)
        ctx2 <- newEventStore(mig2)

        // mig0: has only one verdict (T1)
        recordTs1 = CantonTimestamp.now()
        _ <- insertVerdict(ctx0.verdictStore, "verdict-mig0", recordTs1, migrationId = mig0)

        // mig1: has 1 update (T2), then one verdict (T3)
        recordTs2 = recordTs1.plusSeconds(1)
        tx1 <- insertUpdate(ctx1.updateHistory, recordTs2, "update-mig1")
        updateId1 = tx1.getUpdateId
        recordTs3 = recordTs2.plusSeconds(1)
        _ <- insertVerdict(ctx1.verdictStore, "verdict-mig1", recordTs3, migrationId = mig1)

        // mig2 (current): has 1 update (T4), then one verdict (T5)
        recordTs4 = recordTs3.plusSeconds(1)
        tx2 <- insertUpdate(ctx2.updateHistory, recordTs4, "update-mig2")
        updateId2 = tx2.getUpdateId
        recordTs5 = recordTs4.plusSeconds(1)
        _ <- insertVerdict(ctx2.verdictStore, "verdict-mig2", recordTs5, migrationId = mig2)

        // Query combined events at current migration = mig2
        events <- fetchEvents(ctx2.eventStore, None, mig2, pageLimit)
      } yield {
        // mig0 + mig1 verdicts should be present; mig2 verdict should be filtered (after cap)
        hasVerdict(events, "verdict-mig0") shouldBe true
        hasVerdict(events, "verdict-mig1") shouldBe true
        hasVerdict(events, "verdict-mig2") shouldBe false
        // Ensure updates from mig1/mig2 are visible where applicable
        hasUpdate(events, updateId1) shouldBe true
        hasUpdate(events, updateId2) shouldBe true
      }
    }

    "does not cap the assignments for migrationId < currentMigrationId" in {
      val mig0 = domainMigrationId
      val mig1 = mig0 + 1
      val mig2 = mig1 + 1
      for {
        ctx0 <- newEventStore(mig0)
        ctx1 <- newEventStore(mig1)
        ctx2 <- newEventStore(mig2)

        // mig0: has only one assignment (T1)
        recordTs1 = CantonTimestamp.now()
        assignment0 <- insertAssign(ctx0.updateHistory, recordTs1, "assign-old-mig")

        // mig1: has 1 verdict (T2), then one assignment (T3)
        recordTs2 = recordTs1.plusSeconds(1)
        _ <- insertVerdict(ctx1.verdictStore, "verdict-mig1", recordTs2, migrationId = mig1)
        recordTs3 = recordTs2.plusSeconds(1)
        assignment1 <- insertAssign(ctx1.updateHistory, recordTs3, "assign-mig1")

        // mig2 (current): has 1 verdict (T4), then one assignment (T5)
        recordTs4 = recordTs3.plusSeconds(1)
        _ <- insertVerdict(ctx2.verdictStore, "verdict-mig2", recordTs4, migrationId = mig2)
        recordTs5 = recordTs4.plusSeconds(1)
        assignment2 <- insertAssign(ctx2.updateHistory, recordTs5, "assign-mig2")

        // Query combined events at current migration = mig2
        events <- fetchEvents(ctx2.eventStore, None, mig2, pageLimit)
      } yield {
        // mig0 + mig1 assignments should be present; mig2 assignment should be filtered (after cap)
        hasUpdate(events, assignment0.updateId) shouldBe true
        hasUpdate(events, assignment1.updateId) shouldBe true
        hasUpdate(events, assignment2.updateId) shouldBe false
        hasVerdict(events, "verdict-mig1") shouldBe true
      }
    }

    "does not cap the unassignments for migrationId < currentMigrationId" in {
      val mig0 = domainMigrationId
      val mig1 = mig0 + 1
      val mig2 = mig1 + 1
      for {
        ctx0 <- newEventStore(mig0)
        ctx1 <- newEventStore(mig1)
        ctx2 <- newEventStore(mig2)

        // mig0: has only one unassignment (T1)
        recordTs1 = CantonTimestamp.now()
        unassignment0 <- insertUnassign(ctx0.updateHistory, recordTs1, "unassign-old-mig")

        // mig1: has 1 verdict (T2), then one unassignment (T3)
        recordTs2 = recordTs1.plusSeconds(1)
        _ <- insertVerdict(ctx1.verdictStore, "verdict-mig1", recordTs2, migrationId = mig1)
        recordTs3 = recordTs2.plusSeconds(1)
        unassignment1 <- insertUnassign(ctx1.updateHistory, recordTs3, "unassign-mig1")

        // mig2 (current): has 1 verdict (T4), then one unassignment (T5)
        recordTs4 = recordTs3.plusSeconds(1)
        _ <- insertVerdict(ctx2.verdictStore, "verdict-mig2", recordTs4, migrationId = mig2)
        recordTs5 = recordTs4.plusSeconds(1)
        unassignment2 <- insertUnassign(ctx2.updateHistory, recordTs5, "unassign-mig2")

        // Query combined events at current migration = mig2
        events <- fetchEvents(ctx2.eventStore, None, mig2, pageLimit)
      } yield {
        // mig0 + mig1 unassignments should be present; mig2 unassignment should be filtered (after cap)
        hasUpdate(events, unassignment0.updateId) shouldBe true
        hasUpdate(events, unassignment1.updateId) shouldBe true
        hasUpdate(events, unassignment2.updateId) shouldBe false
        hasVerdict(events, "verdict-mig1") shouldBe true
      }
    }

    "check filtering logic of allowF and getCurrentMigrationCap" in {

      val recordTs1 = CantonTimestamp.now()
      val recordTs2 = recordTs1.plusSeconds(1)
      val recordTs3 = recordTs2.plusSeconds(1)
      val recordTs4 = recordTs3.plusSeconds(1)

      // getCurrentMigrationCap picks min when both defined
      ScanEventStore.getCurrentMigrationCap(
        Some(recordTs1),
        Some(recordTs2),
      ) shouldBe recordTs1
      ScanEventStore.getCurrentMigrationCap(
        Some(recordTs2),
        Some(recordTs1),
      ) shouldBe recordTs1
      ScanEventStore.getCurrentMigrationCap(
        Some(recordTs2),
        Some(recordTs2),
      ) shouldBe recordTs2

      // missing max recordTime gives MinValue
      ScanEventStore.getCurrentMigrationCap(
        None,
        Some(recordTs1),
      ) shouldBe CantonTimestamp.MinValue
      ScanEventStore.getCurrentMigrationCap(
        Some(recordTs1),
        None,
      ) shouldBe CantonTimestamp.MinValue
      ScanEventStore.getCurrentMigrationCap(
        None,
        None,
      ) shouldBe CantonTimestamp.MinValue

      val mig0 = domainMigrationId
      val mig1 = mig0 + 1
      val mig2 = mig1 + 1

      val capMin = CantonTimestamp.MinValue
      val cap2 = recordTs2
      val cap3 = recordTs3

      {
        val allow = ScanEventStore.allowF(
          afterO = None,
          currentMigrationId = mig1,
          currentMigrationCap = capMin,
        )
        allow(mig0, recordTs1) shouldBe true // prior migration is always allowed
        allow(mig0, recordTs2) shouldBe true // prior migration is always allowed
        allow(mig0, recordTs3) shouldBe true // prior migration is always allowed
        allow(mig1, recordTs1) shouldBe false // > cap
        allow(mig1, recordTs2) shouldBe false // > cap
        allow(mig1, recordTs3) shouldBe false // > cap
      }

      {
        val allow = ScanEventStore.allowF(
          afterO = None,
          currentMigrationId = mig1,
          currentMigrationCap = cap2,
        )
        allow(mig0, recordTs1) shouldBe true // prior migration is always allowed
        allow(mig0, recordTs2) shouldBe true // prior migration is always allowed
        allow(mig0, recordTs3) shouldBe true // prior migration is always allowed
        allow(mig1, recordTs1) shouldBe true // <= cap
        allow(mig1, recordTs2) shouldBe true // <= cap
        allow(mig1, recordTs3) shouldBe false // > cap
      }

      {
        val allow = ScanEventStore.allowF(
          afterO = Some((mig0, recordTs1)),
          currentMigrationId = mig1,
          currentMigrationCap = capMin,
        )
        allow(mig0, recordTs1) shouldBe false // equal to after
        allow(mig0, recordTs2) shouldBe true
        allow(mig1, recordTs1) shouldBe false // > cap
      }

      {
        val allow = ScanEventStore.allowF(
          afterO = Some((mig0, recordTs1)),
          currentMigrationId = mig1,
          currentMigrationCap = cap3,
        )
        allow(mig0, recordTs1) shouldBe false // equal to after
        allow(mig0, recordTs2) shouldBe true
        allow(mig1, recordTs1) shouldBe true // <= cap
        allow(mig1, recordTs2) shouldBe true // <= cap
        allow(mig1, recordTs3) shouldBe true // equal to cap is allowed
        allow(mig1, recordTs4) shouldBe false // above cap is blocked
      }

      {
        val allow = ScanEventStore.allowF(
          afterO = Some((mig1, recordTs2)),
          currentMigrationId = mig1,
          currentMigrationCap = cap3,
        )
        allow(mig1, recordTs2) shouldBe false // equal to after
        allow(mig1, recordTs3) shouldBe true // > after and == cap
        allow(mig1, recordTs4) shouldBe false // > cap
      }

      {
        val allow = ScanEventStore.allowF(
          afterO = Some((mig2, recordTs2)),
          currentMigrationId = mig2,
          currentMigrationCap = cap2,
        )
        allow(mig1, recordTs3) shouldBe true // prior migration is always allowed
        allow(mig2, recordTs2) shouldBe false // equal to after
        allow(mig2, recordTs3) shouldBe false // > after and > cap
      }
    }
  }

  private def newUpdateHistory(
      migrationId: Long
  ): Future[UpdateHistory] = {
    val participantId = mkParticipantId("ScanEventStoreTest")
    val uh = new UpdateHistory(
      storage.underlying,
      new DomainMigrationInfo(migrationId, None),
      "scan_event_store_test",
      participantId,
      dsoParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = true,
    )
    uh.ingestionSink.initialize().map(_ => uh)
  }

  private def newVerdictStore() =
    new DbScanVerdictStore(storage.underlying, loggerFactory)

  private def insertUpdate(
      updateHistory: UpdateHistory,
      recordTs: CantonTimestamp,
      workflowId: String,
  ) = {
    implicit val store = updateHistory
    val _ = store
    dummyDomain.ingest { off =>
      mkTx(
        off,
        Seq.empty,
        dummyDomain,
        workflowId = workflowId,
        recordTime = recordTs.toInstant,
      )
    }
  }

  private var ridCounter: Long = 0
  private def nextRid(prefix: String) = { ridCounter += 1; s"$prefix-$ridCounter" }

  private def insertAssign(
      updateHistory: UpdateHistory,
      recordTs: CantonTimestamp,
      contractId: String,
  ) = {
    implicit val store = updateHistory
    val _ = store
    val sourceDomain = SynchronizerId.tryFromString("source::domain")
    val targetDomain = dummyDomain
    targetDomain.assign(
      (
        appRewardCoupon(round = 0, provider = dsoParty, contractId = contractId),
        sourceDomain,
      ),
      nextRid("assign"),
      0,
      recordTime = recordTs,
    )
  }

  private def insertUnassign(
      updateHistory: UpdateHistory,
      recordTs: CantonTimestamp,
      contractId: String,
  ) = {
    implicit val store = updateHistory
    val _ = store
    val sourceDomain = dummyDomain
    val targetDomain = SynchronizerId.tryFromString("target::domain")
    sourceDomain.unassign(
      (
        appRewardCoupon(round = 0, provider = dsoParty, contractId = contractId),
        targetDomain,
      ),
      nextRid("unassign"),
      0,
      recordTime = recordTs,
    )
  }

  private def insertVerdict(
      verdictStore: DbScanVerdictStore,
      updateId: String,
      recordTs: CantonTimestamp,
      participantId: ParticipantId = mkParticipantId(
        "ScanEventStoreTest"
      ),
      informees: Seq[PartyId] = Seq(dsoParty),
      viewId: Int = 0,
      migrationId: Long = domainMigrationId,
  ): Future[Unit] = {
    val verdict = new verdictStore.VerdictT(
      0L,
      migrationId,
      dummyDomain,
      recordTs,
      recordTs,
      participantId.toProtoPrimitive,
      DbScanVerdictStore.VerdictResultDbValue.Accepted,
      0,
      updateId,
      informees.map(_.toProtoPrimitive),
      Seq(viewId),
    )
    val mkViews: Long => Seq[verdictStore.TransactionViewT] = { rowId =>
      Seq(
        new verdictStore.TransactionViewT(
          verdictRowId = rowId,
          viewId = viewId,
          informees = informees.map(_.toProtoPrimitive),
          confirmingParties = Json.arr(),
          subViews = Seq.empty,
        )
      )
    }
    verdictStore.insertVerdictAndTransactionViews(Seq(verdict -> mkViews))
  }

  private val pageLimit = PageLimit.tryCreate(1000)

  private def hasUpdate(events: Seq[ScanEventStore#Event], updateId: String): Boolean =
    events.exists(_._2.exists(_.update.update.updateId == updateId))

  private def hasVerdict(events: Seq[ScanEventStore#Event], updateId: String): Boolean =
    events.exists(_._1.exists(_._1.updateId == updateId))

  private def fetchEvents(
      es: ScanEventStore,
      afterO: Option[(Long, CantonTimestamp)],
      currentMigrationId: Long,
      limit: PageLimit,
  ): Future[Seq[ScanEventStore#Event]] = {
    es.getEvents(afterO, currentMigrationId, limit)(traceContext)
  }

  private case class EventStoreCtx(
      verdictStore: DbScanVerdictStore,
      updateHistory: UpdateHistory,
      eventStore: ScanEventStore,
  )

  private def newEventStore(migrationId: Long = domainMigrationId): Future[EventStoreCtx] =
    for {
      uh <- newUpdateHistory(migrationId)
      vs = newVerdictStore()
      es = new ScanEventStore(vs, uh, loggerFactory)
    } yield EventStoreCtx(vs, uh, es)

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[_] =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()

}
