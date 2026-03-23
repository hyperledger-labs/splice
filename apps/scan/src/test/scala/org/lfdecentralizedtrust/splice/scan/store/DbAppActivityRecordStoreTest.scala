package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.AppActivityRecordT
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, StoreTestBase, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.daml.metrics.api.noop.NoOpMetricsFactory

import scala.concurrent.Future

class DbAppActivityRecordStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  private val migrationId = 0L
  private val roundNumber = 42L

  "DbAppActivityRecordStore" should {

    "insert app activity records" in {
      for {
        (store, historyId) <- newStore()
        verdictRowId <- insertVerdictRow(historyId, CantonTimestamp.now(), "update-1")

        record = AppActivityRecordT(
          verdictRowId = verdictRowId,
          roundNumber = roundNumber,
          appProviderParties = Seq("app1::provider", "app2::provider"),
          appActivityWeights = Seq(100L, 50L),
        )

        _ <- store.insertAppActivityRecords(Seq(record))
        loaded <- store.getRecordByVerdictRowId(verdictRowId)
      } yield {
        loaded.value shouldBe record
      }
    }

    "batch insert multiple app activity records efficiently" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()

        // Insert 50 verdict rows to get valid row_ids
        verdictRowIds <- Future.traverse((0 until 50).toList) { i =>
          insertVerdictRow(historyId, baseTs.plusSeconds(i.toLong), s"update-batch-$i")
        }

        records = verdictRowIds.zipWithIndex.map { case (rowId, i) =>
          mkRecord(
            verdictRowId = rowId,
            roundNumber = roundNumber + i.toLong,
            appProviderParties = Seq(s"app$i::provider"),
            appActivityWeights = Seq(i.toLong * 10),
          )
        }

        _ <- store.insertAppActivityRecords(records)
        // Spot-check first, last and a middle record via row decoders
        first <- store.getRecordByVerdictRowId(verdictRowIds(0))
        middle <- store.getRecordByVerdictRowId(verdictRowIds(25))
        last <- store.getRecordByVerdictRowId(verdictRowIds(49))
      } yield {
        first.value shouldBe records(0)
        middle.value shouldBe records(25)
        last.value shouldBe records(49)
      }
    }

    "handle empty activities" in {
      for {
        (store, historyId) <- newStore()
        verdictRowId <- insertVerdictRow(historyId, CantonTimestamp.now(), "update-empty")

        countBefore <- countRecords()
        record =
          mkRecord(
            verdictRowId,
            100L,
            appProviderParties = Seq.empty,
            appActivityWeights = Seq.empty,
          )

        _ <- store.insertAppActivityRecords(Seq(record))
        countAfter <- countRecords()
      } yield {
        countAfter shouldBe (countBefore + 1)
      }
    }
  }

  "insertVerdictsWithAppActivityRecords" should {

    "insert verdicts and resolve placeholder verdictRowIds in activity records" in {
      for {
        (appStore, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        verdict1 = mkVerdict(verdictStore, "update-combined-1", baseTs)
        verdict2 = mkVerdict(verdictStore, "update-combined-2", baseTs.plusSeconds(1L))

        pendingAppActivity = Seq(
          baseTs -> mkRecord(0L, 10L, Seq("app1::provider"), Seq(100L)),
          baseTs.plusSeconds(1L) -> mkRecord(0L, 11L, Seq("app2::provider"), Seq(200L)),
        )

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict1 -> noViews, verdict2 -> noViews),
          pendingAppActivity,
        )

        // Verify verdicts were inserted
        v1 <- verdictStore.getVerdictByUpdateId("update-combined-1")
        v2 <- verdictStore.getVerdictByUpdateId("update-combined-2")

        // Verify activity records have resolved row_ids (not 0)
        r1 <- appStore.getRecordByVerdictRowId(v1.value.rowId)
        r2 <- appStore.getRecordByVerdictRowId(v2.value.rowId)
      } yield {
        v1 shouldBe defined
        v2 shouldBe defined

        r1.value.verdictRowId shouldBe v1.value.rowId
        r1.value.roundNumber shouldBe 10L
        r1.value.appProviderParties shouldBe Seq("app1::provider")

        r2.value.verdictRowId shouldBe v2.value.rowId
        r2.value.roundNumber shouldBe 11L
        r2.value.appProviderParties shouldBe Seq("app2::provider")
      }
    }

    "insert verdicts without activity records when pendingAppActivity is empty" in {
      for {
        (_, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        verdict = mkVerdict(verdictStore, "update-no-activity", baseTs)

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict -> noViews),
          Seq.empty,
        )

        v <- verdictStore.getVerdictByUpdateId("update-no-activity")
        countAfter <- countRecords()
      } yield {
        v shouldBe defined
        countAfter shouldBe 0L
      }
    }

    "only create activity records for verdicts that have them" in {
      for {
        (appStore, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        // Three verdicts, but only the first and third have activity records
        verdict1 = mkVerdict(verdictStore, "update-with-1", baseTs)
        verdict2 = mkVerdict(verdictStore, "update-without", baseTs.plusSeconds(1L))
        verdict3 = mkVerdict(verdictStore, "update-with-2", baseTs.plusSeconds(2L))

        pendingAppActivity = Seq(
          baseTs -> mkRecord(0L, 10L, Seq("app1::provider"), Seq(100L)),
          baseTs.plusSeconds(2L) -> mkRecord(0L, 12L, Seq("app3::provider"), Seq(300L)),
        )

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict1 -> noViews, verdict2 -> noViews, verdict3 -> noViews),
          pendingAppActivity,
        )

        v1 <- verdictStore.getVerdictByUpdateId("update-with-1")
        v2 <- verdictStore.getVerdictByUpdateId("update-without")
        v3 <- verdictStore.getVerdictByUpdateId("update-with-2")

        r1 <- appStore.getRecordByVerdictRowId(v1.value.rowId)
        r2 <- appStore.getRecordByVerdictRowId(v2.value.rowId)
        r3 <- appStore.getRecordByVerdictRowId(v3.value.rowId)

        totalRecords <- countRecords()
      } yield {
        // All three verdicts should be inserted
        v1 shouldBe defined
        v2 shouldBe defined
        v3 shouldBe defined

        // Only first and third have activity records
        r1.value.roundNumber shouldBe 10L
        r1.value.appProviderParties shouldBe Seq("app1::provider")

        r2 shouldBe None

        r3.value.roundNumber shouldBe 12L
        r3.value.appProviderParties shouldBe Seq("app3::provider")

        totalRecords shouldBe 2L
      }
    }

    "skip activity records with no matching verdict timestamp" in {
      for {
        (appStore, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        verdict = mkVerdict(verdictStore, "update-mismatch", baseTs)

        // Activity record has a timestamp that doesn't match any verdict
        unmatchedTs = baseTs.plusSeconds(999L)
        pendingAppActivity = Seq(
          unmatchedTs -> mkRecord(0L, 42L, Seq("orphan::provider"), Seq(300L))
        )

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict -> noViews),
          pendingAppActivity,
        )

        v <- verdictStore.getVerdictByUpdateId("update-mismatch")
        r <- appStore.getRecordByVerdictRowId(v.value.rowId)
        countAfter <- countRecords()
      } yield {
        v shouldBe defined
        r shouldBe None
        countAfter shouldBe 0L
      }
    }
  }

  "earliestRoundWithCompleteAppActivity" should {

    "return None when no activity records exist" in {
      for {
        (store, _) <- newStore()
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return None when only one round has records" in {
      for {
        (store, historyId) <- newStore()
        rowId <- insertVerdictRow(historyId, CantonTimestamp.now(), "update-single-round")
        _ <- store.insertAppActivityRecords(
          Seq(mkRecord(rowId, 42L, Seq("app1::provider"), Seq(100L)))
        )
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return the second round when two consecutive rounds have records" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        rowId1 <- insertVerdictRow(historyId, baseTs, "update-earliest-42")
        rowId2 <- insertVerdictRow(historyId, baseTs.plusSeconds(1L), "update-earliest-43")
        _ <- store.insertAppActivityRecords(
          Seq(
            mkRecord(rowId1, 42L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2, 43L, Seq("app1::provider"), Seq(200L)),
          )
        )
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        // 43 has prior round 42, so 43 is the earliest complete round
        result.value shouldBe 43L
      }
    }

    "return the earliest complete round when multiple consecutive rounds have records" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        rowId1 <- insertVerdictRow(historyId, baseTs, "update-multi-10")
        rowId2 <- insertVerdictRow(historyId, baseTs.plusSeconds(1L), "update-multi-11")
        rowId3 <- insertVerdictRow(historyId, baseTs.plusSeconds(2L), "update-multi-12")
        _ <- store.insertAppActivityRecords(
          Seq(
            mkRecord(rowId1, 10L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2, 11L, Seq("app1::provider"), Seq(200L)),
            mkRecord(rowId3, 12L, Seq("app1::provider"), Seq(300L)),
          )
        )
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        // 11 has prior round 10, so 11 is earliest complete
        result.value shouldBe 11L
      }
    }

    "return None when rounds are not consecutive" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        rowId1 <- insertVerdictRow(historyId, baseTs, "update-gap-10")
        rowId2 <- insertVerdictRow(historyId, baseTs.plusSeconds(1L), "update-gap-12")
        _ <- store.insertAppActivityRecords(
          Seq(
            mkRecord(rowId1, 10L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2, 12L, Seq("app1::provider"), Seq(200L)),
          )
        )
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        // No round has a prior round with records (11 is missing)
        result shouldBe None
      }
    }

    "not return the first round (it has no prior round)" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        rowId1 <- insertVerdictRow(historyId, baseTs, "update-latest-20")
        rowId2 <- insertVerdictRow(historyId, baseTs.plusSeconds(1L), "update-latest-21")
        _ <- store.insertAppActivityRecords(
          Seq(
            mkRecord(rowId1, 20L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2, 21L, Seq("app1::provider"), Seq(200L)),
          )
        )
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        // 21 has prior round 20, but 20 has no prior round
        result.value shouldBe 21L
      }
    }

    "only consider records from own history_id" in {
      for {
        (store1, historyId1) <- newStore()
        (store2, historyId2) <- newStore()
        baseTs = CantonTimestamp.now()
        // store2 has consecutive rounds 10,11
        rowId2a <- insertVerdictRow(historyId2, baseTs, "update-other-10")
        rowId2b <- insertVerdictRow(historyId2, baseTs.plusSeconds(1L), "update-other-11")
        _ <- store2.insertAppActivityRecords(
          Seq(
            mkRecord(rowId2a, 10L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2b, 11L, Seq("app1::provider"), Seq(200L)),
          )
        )
        // store1 has only one round — no consecutive pair
        rowId1 <- insertVerdictRow(historyId1, baseTs.plusSeconds(2L), "update-own-50")
        _ <- store1.insertAppActivityRecords(
          Seq(mkRecord(rowId1, 50L, Seq("app1::provider"), Seq(300L)))
        )
        result <- store1.earliestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }
  }

  "latestRoundWithCompleteAppActivity" should {

    "return None when no activity records exist" in {
      for {
        (store, _) <- newStore()
        result <- store.latestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return None when only one round has records" in {
      for {
        (store, historyId) <- newStore()
        rowId <- insertVerdictRow(historyId, CantonTimestamp.now(), "update-single-round")
        _ <- store.insertAppActivityRecords(
          Seq(mkRecord(rowId, 42L, Seq("app1::provider"), Seq(100L)))
        )
        result <- store.latestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return the second round when two consecutive rounds have records" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        rowId1 <- insertVerdictRow(historyId, baseTs, "update-latest-42")
        rowId2 <- insertVerdictRow(historyId, baseTs.plusSeconds(1L), "update-latest-43")
        _ <- store.insertAppActivityRecords(
          Seq(
            mkRecord(rowId1, 42L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2, 43L, Seq("app1::provider"), Seq(200L)),
          )
        )
        result <- store.latestRoundWithCompleteAppActivity()
      } yield {
        // 43 has prior round 42, so 43 is both earliest and latest complete
        result.value shouldBe 43L
      }
    }

    "return the latest complete round when multiple consecutive rounds have records" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        rowId1 <- insertVerdictRow(historyId, baseTs, "update-multi-10")
        rowId2 <- insertVerdictRow(historyId, baseTs.plusSeconds(1L), "update-multi-11")
        rowId3 <- insertVerdictRow(historyId, baseTs.plusSeconds(2L), "update-multi-12")
        _ <- store.insertAppActivityRecords(
          Seq(
            mkRecord(rowId1, 10L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2, 11L, Seq("app1::provider"), Seq(200L)),
            mkRecord(rowId3, 12L, Seq("app1::provider"), Seq(300L)),
          )
        )
        result <- store.latestRoundWithCompleteAppActivity()
      } yield {
        // 12 has prior round 11, so 12 is the latest complete
        result.value shouldBe 12L
      }
    }

    "return None when rounds are not consecutive" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        rowId1 <- insertVerdictRow(historyId, baseTs, "update-gap-10")
        rowId2 <- insertVerdictRow(historyId, baseTs.plusSeconds(1L), "update-gap-12")
        _ <- store.insertAppActivityRecords(
          Seq(
            mkRecord(rowId1, 10L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2, 12L, Seq("app1::provider"), Seq(200L)),
          )
        )
        result <- store.latestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "only consider records from own history_id" in {
      for {
        (store1, historyId1) <- newStore()
        (store2, historyId2) <- newStore()
        baseTs = CantonTimestamp.now()
        // store2 has consecutive rounds 10,11
        rowId2a <- insertVerdictRow(historyId2, baseTs, "update-other-10")
        rowId2b <- insertVerdictRow(historyId2, baseTs.plusSeconds(1L), "update-other-11")
        _ <- store2.insertAppActivityRecords(
          Seq(
            mkRecord(rowId2a, 10L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2b, 11L, Seq("app1::provider"), Seq(200L)),
          )
        )
        // store1 has only one round — no consecutive pair
        rowId1 <- insertVerdictRow(historyId1, baseTs.plusSeconds(2L), "update-own-50")
        _ <- store1.insertAppActivityRecords(
          Seq(mkRecord(rowId1, 50L, Seq("app1::provider"), Seq(300L)))
        )
        result <- store1.latestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }
  }

  private def mkRecord(
      verdictRowId: Long,
      roundNumber: Long,
      appProviderParties: Seq[String],
      appActivityWeights: Seq[Long],
  ): AppActivityRecordT =
    AppActivityRecordT(
      verdictRowId = verdictRowId,
      roundNumber = roundNumber,
      appProviderParties = appProviderParties,
      appActivityWeights = appActivityWeights,
    )

  private val testDomain = SynchronizerId.tryFromString("test::domain")

  private val storeCounter = new java.util.concurrent.atomic.AtomicLong(1)

  /** Creates a new store and returns it along with a unique history_id
    * obtained from UpdateHistory initialization.
    */
  private def newStore(): Future[(DbAppActivityRecordStore, Long)] = {
    val n = storeCounter.getAndIncrement()
    val participantId = mkParticipantId(s"activity-test-$n")
    val updateHistory = new UpdateHistory(
      storage.underlying,
      new DomainMigrationInfo(migrationId, None),
      s"app_activity_test_$n",
      participantId,
      dsoParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = false,
      HistoryMetrics(NoOpMetricsFactory, migrationId),
    )
    updateHistory.ingestionSink.initialize().map { _ =>
      val store = new DbAppActivityRecordStore(
        storage.underlying,
        updateHistory,
        loggerFactory,
      )
      (store, updateHistory.historyId)
    }
  }

  /** Creates both an app activity record store and a verdict store backed by
    * the same UpdateHistory, for testing insertVerdictsWithAppActivityRecords.
    */
  private def newStores(): Future[(DbAppActivityRecordStore, DbScanVerdictStore)] = {
    val participantId = mkParticipantId("activity-test")
    val updateHistory = new UpdateHistory(
      storage.underlying,
      new DomainMigrationInfo(migrationId, None),
      "app_activity_combined_test",
      participantId,
      dsoParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = false,
      HistoryMetrics(NoOpMetricsFactory, migrationId),
    )
    updateHistory.ingestionSink.initialize().map { _ =>
      val appStore = new DbAppActivityRecordStore(
        storage.underlying,
        updateHistory,
        loggerFactory,
      )
      val verdictStore = new DbScanVerdictStore(
        storage.underlying,
        updateHistory,
        Some(appStore),
        loggerFactory,
      )
      (appStore, verdictStore)
    }
  }

  private def mkVerdict(
      verdictStore: DbScanVerdictStore,
      updateId: String,
      recordTs: CantonTimestamp,
  ): verdictStore.VerdictT =
    new verdictStore.VerdictT(
      rowId = 0L,
      migrationId = migrationId,
      domainId = testDomain,
      recordTime = recordTs,
      finalizationTime = recordTs,
      submittingParticipantUid = "participant1",
      verdictResult = DbScanVerdictStore.VerdictResultDbValue.Accepted,
      mediatorGroup = 0,
      updateId = updateId,
      submittingParties = Seq.empty,
      transactionRootViews = Seq.empty,
      trafficSummaryO = None,
    )

  private val noViews: Long => Seq[DbScanVerdictStore.TransactionViewT] = _ => Seq.empty

  /** Insert a minimal verdict row into scan_verdict_store and return its generated row_id. */
  private def insertVerdictRow(
      historyId: Long,
      recordTime: CantonTimestamp,
      updateId: String,
  ): Future[Long] = {
    import storage.api.jdbcProfile.api.*
    futureUnlessShutdownToFuture(
      storage.underlying
        .queryAndUpdate(
          sql"""
          insert into scan_verdict_store(
            history_id, migration_id, domain_id, record_time, finalization_time,
            submitting_participant_uid, verdict_result, mediator_group,
            update_id, submitting_parties, transaction_root_views
          ) values (
            $historyId, $migrationId, 'test-domain', $recordTime, $recordTime,
            'participant1', 1, 0,
            $updateId, array[]::text[], array[]::integer[]
          ) returning row_id
        """.as[Long].head,
          "test.insertVerdictRow",
        )
    )
  }

  /** Test helper to count records in the database */
  private def countRecords(): Future[Long] = {
    import storage.api.jdbcProfile.api.*
    futureUnlessShutdownToFuture(
      storage.underlying
        .query(
          sql"""
          select count(*)
          from app_activity_record_store
        """.as[Long].head,
          "test.countRecords",
        )
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}
