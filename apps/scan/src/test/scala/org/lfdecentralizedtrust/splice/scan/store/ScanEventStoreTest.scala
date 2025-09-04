package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.StoreTest
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import io.circe.Json

import scala.concurrent.Future

class ScanEventStoreTest
    extends StoreTest
    with HasExecutionContext
    with org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest {

  "ScanEventStore" should {
    "combine verdict and update events by update_id" in {
      for {
        ctx <- newEventStore()
        recordTs = CantonTimestamp.now()
        tx <- createAmulet(ctx.updateHistory, recordTs, "scan-event-store-test")
        updateId = tx.getUpdateId

        _ <- insertVerdict(ctx.verdictStore, updateId, recordTs)

        eventOpt <- ctx.eventStore.getEventByUpdateId(updateId)
      } yield {
        val (verdictOpt, updateOpt) = eventOpt.value
        verdictOpt.value._2.size shouldBe 1
        val view = verdictOpt.value._2.head
        view.verdictRowId shouldBe verdictOpt.value._1.rowId
        verdictOpt.value._1.recordTime shouldBe updateOpt.value.update.update.recordTime
      }
    }

    "Cap the updates till the latest verdict" in {
      for {
        ctx <- newEventStore()
        // First update
        recordTs1 = CantonTimestamp.now()
        tx1 <- createAmulet(ctx.updateHistory, recordTs1, "scan-event-store-test-1")
        updateId1 = tx1.getUpdateId
        _ <- insertVerdict(ctx.verdictStore, updateId1, recordTs1)

        // Second update (with no verdict).
        recordTs2 = recordTs1.plusSeconds(1)
        tx2 <- createAmulet(ctx.updateHistory, recordTs2, "scan-event-store-test-2")
        updateId2 = tx2.getUpdateId

        // Fetch without cursor
        events1 <- ctx.eventStore.getEventsReference(
          None,
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch with cursor
        events2 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch after recordTs1
        events3 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        updateEventOpt <- ctx.eventStore.getEventByUpdateId(updateId2)
      } yield {
        // Expect only the first update (with a verdict) to appear for the current migration
        events1.exists(_._1.exists(_._1.updateId == updateId1)) shouldBe true

        // Ensure the second, later update without a verdict is filtered out
        events1.exists(_._2.exists(_.update.update.updateId == updateId2)) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true

        // But fetching by the specific update_id still returns the update
        val (verdictO, updateO) = updateEventOpt.value
        verdictO shouldBe None
        updateO.map(_.update.update.updateId) shouldBe Some(updateId2)
      }
    }

    "Cap the verdict till the latest update" in {
      for {
        ctx <- newEventStore()
        // First update
        recordTs1 = CantonTimestamp.now()
        tx1 <- createAmulet(ctx.updateHistory, recordTs1, "scan-event-store-test-1")
        updateId1 = tx1.getUpdateId
        _ <- insertVerdict(ctx.verdictStore, updateId1, recordTs1)

        // Second verdict (with no update).
        recordTs2 = recordTs1.plusSeconds(1)
        updateId2 = "verdict-later-update-id"
        _ <- insertVerdict(ctx.verdictStore, updateId2, recordTs2)

        // Fetch without cursor
        events1 <- ctx.eventStore.getEventsReference(
          None,
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch with cursor
        events2 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch after recordTs1
        events3 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        updateEventOpt <- ctx.eventStore.getEventByUpdateId(updateId2)
      } yield {
        // Expect only the first update (with a verdict) to appear for the current migration
        events1.exists(_._1.exists(_._1.updateId == updateId1)) shouldBe true

        // Ensure the second, later verdict without an update is filtered out
        events1.exists(_._2.exists(_.update.update.updateId == updateId2)) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true

        // But fetching by the specific update_id still returns the event with verdict
        val (verdictO, updateO) = updateEventOpt.value
        updateO shouldBe None
        verdictO.map(_._1.updateId) shouldBe Some(updateId2)
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
        tx <- createAmulet(ctx.updateHistory, recordTs3, "scan-event-store-test-mixed")
        updateId = tx.getUpdateId
        _ <- insertVerdict(ctx.verdictStore, updateId, recordTs3)

        // Fetch without cursor
        events1 <- ctx.eventStore.getEventsReference(
          None,
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch with cursor
        events2 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch after recordTs1
        events3 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
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
        tx1 <- createAmulet(ctx0.updateHistory, recordTs1, "scan-event-store-test-mig0")
        updateId1 = tx1.getUpdateId
        _ <- insertVerdict(ctx0.verdictStore, updateId1, recordTs1, migrationId = mig0)

        // Insert an update+verdict in migration 1
        recordTs2 = recordTs1.plusSeconds(1)
        tx2 <- createAmulet(ctx1.updateHistory, recordTs2, "scan-event-store-test-mig1")
        updateId2 = tx2.getUpdateId
        _ <- insertVerdict(ctx1.verdictStore, updateId2, recordTs2, migrationId = mig1)

        // Query combined events at current migration = mig1
        events <- ctx1.eventStore.getEventsReference(
          None,
          mig1,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        events2 <- ctx1.eventStore.getEventsReference(
          Some((mig0, recordTs1.minusSeconds(1))),
          mig1,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // after recordTs1
        events3 <- ctx1.eventStore.getEventsReference(
          Some((mig0, recordTs1)),
          mig1,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch by id works across migrationIds
        e1 <- ctx1.eventStore.getEventByUpdateId(updateId1)
        e2 <- ctx1.eventStore.getEventByUpdateId(updateId2)
      } yield {
        // Cursor before ts1 should match no-cursor results
        events shouldBe events2

        // Should contain both updateIds
        events.exists(_._2.exists(_.update.update.updateId == updateId1)) shouldBe true
        events.exists(_._2.exists(_.update.update.updateId == updateId2)) shouldBe true

        // Cursor at ts1 for mig0 should exclude mig0 event and include only mig1
        events3.size shouldBe 1
        events3.exists(_._2.exists(_.update.update.updateId == updateId1)) shouldBe false
        events3.exists(_._2.exists(_.update.update.updateId == updateId2)) shouldBe true

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
        reassignment <- {
          implicit val store: UpdateHistory = ctx.updateHistory
          val sourceDomain = com.digitalasset.canton.topology.SynchronizerId
            .tryFromString("source::domain")
          val targetDomain = dummyDomain
          targetDomain.assign(
            (
              appRewardCoupon(round = 0, provider = dsoParty, contractId = "assign-cid"),
              sourceDomain,
            ),
            "rid-assign",
            0,
            recordTime = recordTs,
          )
        }

        events <- ctx.eventStore.getEventsReference(
          None,
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )

        // currently does not provide reassignment updates, same behaviour as getUpdateByIdV2
        // byId <- ctx.eventStore.getEventByUpdateId(reassignment.updateId)
      } yield {
        events.exists(_._2.exists(_.update.update.updateId == reassignment.updateId)) shouldBe true

        // val (verdictO, updateO) = byId.value
        // verdictO shouldBe None
        // updateO.map(_.update.update.updateId) shouldBe Some(reassignment.updateId)
      }
    }

    "provide unassignment updates" in {
      for {
        ctx <- newEventStore()
        recordTs = CantonTimestamp.now()

        // Create an unassignment (source -> target)
        reassignment <- {
          implicit val store: UpdateHistory = ctx.updateHistory
          val sourceDomain = dummyDomain
          val targetDomain = com.digitalasset.canton.topology.SynchronizerId
            .tryFromString("target::domain")
          sourceDomain.unassign(
            (
              appRewardCoupon(round = 0, provider = dsoParty, contractId = "unassign-cid"),
              targetDomain,
            ),
            "rid-unassign",
            0,
            recordTime = recordTs,
          )
        }

        events <- ctx.eventStore.getEventsReference(
          None,
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )

        // currently does not provide reassignment updates, same behaviour as getUpdateByIdV2
        // byId <- ctx.eventStore.getEventByUpdateId(reassignment.updateId)
      } yield {
        events.exists(_._2.exists(_.update.update.updateId == reassignment.updateId)) shouldBe true

        // val (verdictO, updateO) = byId.value
        // verdictO shouldBe None
        // updateO.map(_.update.update.updateId) shouldBe Some(reassignment.updateId)
      }
    }

    "Cap the assignments till the latest verdict" in {
      for {
        ctx <- newEventStore()
        // First assignment
        recordTs1 = CantonTimestamp.now()
        assignment1 <- {
          implicit val store: UpdateHistory = ctx.updateHistory
          val sourceDomain = com.digitalasset.canton.topology.SynchronizerId
            .tryFromString("source::domain")
          val targetDomain = dummyDomain
          targetDomain.assign(
            (
              appRewardCoupon(round = 0, provider = dsoParty, contractId = "assign-cap-1"),
              sourceDomain,
            ),
            "rid-assign-cap-1",
            0,
            recordTime = recordTs1,
          )
        }
        _ <- insertVerdict(ctx.verdictStore, assignment1.updateId, recordTs1)

        // Second assignment (no verdict)
        recordTs2 = recordTs1.plusSeconds(1)
        assignment2 <- {
          implicit val store: UpdateHistory = ctx.updateHistory
          val sourceDomain = com.digitalasset.canton.topology.SynchronizerId
            .tryFromString("source::domain")
          val targetDomain = dummyDomain
          targetDomain.assign(
            (
              appRewardCoupon(round = 0, provider = dsoParty, contractId = "assign-cap-2"),
              sourceDomain,
            ),
            "rid-assign-cap-2",
            0,
            recordTime = recordTs2,
          )
        }

        // Fetch without cursor
        events1 <- ctx.eventStore.getEventsReference(
          None,
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch with cursor
        events2 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch after recordTs1
        events3 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
      } yield {
        // Only first assignment appears
        events1.exists(_._1.exists(_._1.updateId == assignment1.updateId)) shouldBe true

        // Later assignment without verdict is filtered
        events1.exists(_._2.exists(_.update.update.updateId == assignment2.updateId)) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true
      }
    }

    "Cap the verdict till the latest assignment" in {
      for {
        ctx <- newEventStore()
        // First assignment
        recordTs1 = CantonTimestamp.now()
        assignment1 <- {
          implicit val store: UpdateHistory = ctx.updateHistory
          val sourceDomain = com.digitalasset.canton.topology.SynchronizerId
            .tryFromString("source::domain")
          val targetDomain = dummyDomain
          targetDomain.assign(
            (
              appRewardCoupon(round = 0, provider = dsoParty, contractId = "assign-cap-3"),
              sourceDomain,
            ),
            "rid-assign-cap-3",
            0,
            recordTime = recordTs1,
          )
        }
        _ <- insertVerdict(ctx.verdictStore, assignment1.updateId, recordTs1)

        // Second verdict (no update)
        recordTs2 = recordTs1.plusSeconds(1)
        updateId2 = "verdict-later-assignment-id"
        _ <- insertVerdict(ctx.verdictStore, updateId2, recordTs2)

        // Fetch without cursor
        events1 <- ctx.eventStore.getEventsReference(
          None,
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch with cursor
        events2 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch after recordTs1
        events3 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        updateEventOpt <- ctx.eventStore.getEventByUpdateId(updateId2)
      } yield {
        // Only first assignment appears
        events1.exists(_._1.exists(_._1.updateId == assignment1.updateId)) shouldBe true
        // Later verdict without update is filtered
        events1.exists(_._2.exists(_.update.update.updateId == updateId2)) shouldBe false

        events1 shouldBe events2

        // After recordTs1, empty due to capping
        events3.isEmpty shouldBe true

        // Fetching by id returns verdict-only
        val (verdictO, updateO) = updateEventOpt.value
        updateO shouldBe None
        verdictO.map(_._1.updateId) shouldBe Some(updateId2)
      }
    }

    "Cap the unassignments till the latest verdict" in {
      for {
        ctx <- newEventStore()
        // First unassignment
        recordTs1 = CantonTimestamp.now()
        unassignment1 <- {
          implicit val store: UpdateHistory = ctx.updateHistory
          val sourceDomain = dummyDomain
          val targetDomain = com.digitalasset.canton.topology.SynchronizerId
            .tryFromString("target::domain")
          sourceDomain.unassign(
            (
              appRewardCoupon(round = 0, provider = dsoParty, contractId = "unassign-cap-1"),
              targetDomain,
            ),
            "rid-unassign-cap-1",
            0,
            recordTime = recordTs1,
          )
        }
        _ <- insertVerdict(ctx.verdictStore, unassignment1.updateId, recordTs1)

        // Second unassignment (no verdict)
        recordTs2 = recordTs1.plusSeconds(1)
        unassignment2 <- {
          implicit val store: UpdateHistory = ctx.updateHistory
          val sourceDomain = dummyDomain
          val targetDomain = com.digitalasset.canton.topology.SynchronizerId
            .tryFromString("target::domain")
          sourceDomain.unassign(
            (
              appRewardCoupon(round = 0, provider = dsoParty, contractId = "unassign-cap-2"),
              targetDomain,
            ),
            "rid-unassign-cap-2",
            0,
            recordTime = recordTs2,
          )
        }

        // Fetch without cursor
        events1 <- ctx.eventStore.getEventsReference(
          None,
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch with cursor
        events2 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch after recordTs1
        events3 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
      } yield {
        // Only first unassignment appears
        events1.exists(_._1.exists(_._1.updateId == unassignment1.updateId)) shouldBe true

        // Later unassignment without verdict is filtered
        events1.exists(
          _._2.exists(_.update.update.updateId == unassignment2.updateId)
        ) shouldBe false

        events1 shouldBe events2

        events3.isEmpty shouldBe true

      }
    }

    "Cap the verdict till the latest unassignment" in {
      for {
        ctx <- newEventStore()
        // First unassignment
        recordTs1 = CantonTimestamp.now()
        unassignment1 <- {
          implicit val store: UpdateHistory = ctx.updateHistory
          val sourceDomain = dummyDomain
          val targetDomain = com.digitalasset.canton.topology.SynchronizerId
            .tryFromString("target::domain")
          sourceDomain.unassign(
            (
              appRewardCoupon(round = 0, provider = dsoParty, contractId = "unassign-cap-3"),
              targetDomain,
            ),
            "rid-unassign-cap-3",
            0,
            recordTime = recordTs1,
          )
        }
        _ <- insertVerdict(ctx.verdictStore, unassignment1.updateId, recordTs1)

        // Second verdict (no update)
        recordTs2 = recordTs1.plusSeconds(1)
        updateId2 = "verdict-later-unassignment-id"
        _ <- insertVerdict(ctx.verdictStore, updateId2, recordTs2)

        // Fetch without cursor
        events1 <- ctx.eventStore.getEventsReference(
          None,
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch with cursor
        events2 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1.minusSeconds(1))),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        // Fetch after recordTs1
        events3 <- ctx.eventStore.getEventsReference(
          Some((domainMigrationId, recordTs1)),
          domainMigrationId,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
        updateEventOpt <- ctx.eventStore.getEventByUpdateId(updateId2)
      } yield {
        // Only first unassignment appears
        events1.exists(_._1.exists(_._1.updateId == unassignment1.updateId)) shouldBe true
        // Later verdict without update is filtered
        events1.exists(_._2.exists(_.update.update.updateId == updateId2)) shouldBe false

        events1 shouldBe events2

        // After recordTs1, empty due to capping
        events3.isEmpty shouldBe true

        // Fetching by id returns verdict-only
        val (verdictO, updateO) = updateEventOpt.value
        updateO shouldBe None
        verdictO.map(_._1.updateId) shouldBe Some(updateId2)
      }
    }

    "does not cap the updates for migrationId < currentMigrationId" in {
      val mig0 = domainMigrationId
      val mig1 = nextDomainMigrationId
      for {
        ctx0 <- newEventStore(mig0)
        ctx1 <- newEventStore(mig1)

        recordTs1 = CantonTimestamp.now()

        // Insert an update-only in prior migration
        txOld <- createAmulet(ctx0.updateHistory, recordTs1, "scan-event-store-test-cap-upd-old")
        updateId1 = txOld.getUpdateId

        recordTs2 = recordTs1.plusSeconds(1)
        txCur <- createAmulet(ctx1.updateHistory, recordTs2, "scan-event-store-test-cap-upd-cur")
        updateId2 = txCur.getUpdateId
        _ <- insertVerdict(ctx1.verdictStore, updateId2, recordTs2, migrationId = mig1)

        events <- ctx1.eventStore.getEventsReference(
          None,
          mig1,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
      } yield {
        // Prior-migration update after the cap should still be present
        events.exists(_._2.exists(_.update.update.updateId == updateId1)) shouldBe true
        events.exists(_._2.exists(_.update.update.updateId == updateId2)) shouldBe true
      }
    }

    "does not cap the verdicts for migrationId < currentMigrationId" in {
      val mig0 = domainMigrationId
      val mig1 = nextDomainMigrationId
      for {
        ctx0 <- newEventStore(mig0)
        ctx1 <- newEventStore(mig1)

        recordTs1 = CantonTimestamp.now()

        // Insert a verdict-only in prior migration
        updateId1 = "verdict-only-prior-mig"
        _ <- insertVerdict(ctx0.verdictStore, updateId1, recordTs1, migrationId = mig0)

        recordTs2 = recordTs1.plusSeconds(1)
        txCur <- createAmulet(ctx1.updateHistory, recordTs2, "scan-event-store-test-cap-upd-cur")
        updateId2 = txCur.getUpdateId
        _ <- insertVerdict(ctx1.verdictStore, updateId2, recordTs2, migrationId = mig1)

        events <- ctx1.eventStore.getEventsReference(
          None,
          mig1,
          org.lfdecentralizedtrust.splice.store.PageLimit.tryCreate(10),
        )
      } yield {
        // Prior-migration verdict after the cap should still be present
        events.exists(_._1.exists(_._1.updateId == updateId1)) shouldBe true
        events.exists(_._2.exists(_.update.update.updateId == updateId2)) shouldBe true
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

  private def createAmulet(
      updateHistory: UpdateHistory,
      recordTs: CantonTimestamp,
      workflowId: String,
  ) = {
    implicit val store = updateHistory
    dummyDomain.create(
      amuletRules(),
      workflowId = workflowId,
      recordTime = recordTs.toInstant,
    )
  }

  private def insertVerdict(
      verdictStore: DbScanVerdictStore,
      updateId: String,
      recordTs: CantonTimestamp,
      participantId: com.digitalasset.canton.topology.ParticipantId = mkParticipantId(
        "ScanEventStoreTest"
      ),
      informees: Seq[com.digitalasset.canton.topology.PartyId] = Seq(dsoParty),
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
    verdictStore.insertVerdictAndTransactionViews(verdict, mkViews)
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
