package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.{
  ActivityIngestionMetaCheck,
  DbAppActivityRecordStore,
}
import org.lfdecentralizedtrust.splice.scan.store.db.ActivityIngestionMetaCheck.*
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
        // Batch fetch a subset of records
        batchIds = Seq(verdictRowIds(0), verdictRowIds(10), verdictRowIds(49))
        batchResult <- store.getRecordsByVerdictRowIds(batchIds)
        // Batch fetch with empty input
        emptyResult <- store.getRecordsByVerdictRowIds(Seq.empty)
        // Batch fetch with a non-existent id mixed in
        missingId = -999L
        partialResult <- store.getRecordsByVerdictRowIds(Seq(verdictRowIds(0), missingId))
      } yield {
        first.value shouldBe records(0)
        middle.value shouldBe records(25)
        last.value shouldBe records(49)
        // Batch assertions
        batchResult should have size 3
        batchResult(verdictRowIds(0)) shouldBe records(0)
        batchResult(verdictRowIds(10)) shouldBe records(10)
        batchResult(verdictRowIds(49)) shouldBe records(49)
        emptyResult shouldBe empty
        partialResult should have size 1
        partialResult(verdictRowIds(0)) shouldBe records(0)
        partialResult.get(missingId) shouldBe None
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

        appActivityRecords = Seq(
          baseTs -> mkRecord(0L, 10L, Seq("app1::provider"), Seq(100L)),
          baseTs.plusSeconds(1L) -> mkRecord(0L, 11L, Seq("app2::provider"), Seq(200L)),
        )

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict1 -> noViews, verdict2 -> noViews),
          appActivityRecords,
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

    "insert verdicts without activity records when appActivityRecords is empty" in {
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

        appActivityRecords = Seq(
          baseTs -> mkRecord(0L, 10L, Seq("app1::provider"), Seq(100L)),
          baseTs.plusSeconds(2L) -> mkRecord(0L, 12L, Seq("app3::provider"), Seq(300L)),
        )

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict1 -> noViews, verdict2 -> noViews, verdict3 -> noViews),
          appActivityRecords,
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
        appActivityRecords = Seq(
          unmatchedTs -> mkRecord(0L, 42L, Seq("orphan::provider"), Seq(300L))
        )

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict -> noViews),
          appActivityRecords,
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

    "return None when no meta record exists" in {
      for {
        (store, _) <- newStore()
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return None when no activity records exist" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(1, 0, 0L, 0L)
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return None when only one round has records" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 42L)
        rowId <- insertVerdictRow(historyId, baseTs, "update-single-round")
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
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 42L)
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
        result.value shouldBe 43L
      }
    }

    "return the earliest complete round when multiple consecutive rounds have records" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 10L)
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
        result.value shouldBe 11L
      }
    }

    "return None when rounds are not consecutive" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 10L)
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
        result shouldBe None
      }
    }

    "not return the first round (it has no prior round)" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 20L)
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
        result.value shouldBe 21L
      }
    }

    "skip rounds before started_ingesting_at" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        // Rounds 10,11 are before ingestion start; 20,21 are after
        rowId1 <- insertVerdictRow(historyId, baseTs, "update-pre-10")
        rowId2 <- insertVerdictRow(historyId, baseTs.plusSeconds(1L), "update-pre-11")
        rowId3 <- insertVerdictRow(historyId, baseTs.plusSeconds(10L), "update-post-20")
        rowId4 <- insertVerdictRow(historyId, baseTs.plusSeconds(11L), "update-post-21")
        _ <- store.insertAppActivityRecords(
          Seq(
            mkRecord(rowId1, 10L, Seq("app1::provider"), Seq(100L)),
            mkRecord(rowId2, 11L, Seq("app1::provider"), Seq(200L)),
            mkRecord(rowId3, 20L, Seq("app1::provider"), Seq(300L)),
            mkRecord(rowId4, 21L, Seq("app1::provider"), Seq(400L)),
          )
        )
        // Start ingestion at the time of round 20
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.plusSeconds(10L).toMicros, 20L)
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        // Round 10,11 are before start, so earliest complete is 21 (not 11)
        result.value shouldBe 21L
      }
    }

    "only consider records from own history_id" in {
      for {
        (store1, historyId1) <- newStore()
        (store2, historyId2) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- store2.insertActivityRecordMeta(1, 0, baseTs.toMicros, 10L)
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
        _ <- store1.insertActivityRecordMeta(1, 0, baseTs.plusSeconds(2L).toMicros, 50L)
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

  "getActivityRecordMeta" should {

    "return None when no meta row exists" in {
      for {
        (store, _) <- newStore()
        result <- store.getActivityRecordMeta()
      } yield {
        result shouldBe None
      }
    }

    "return the meta row after insert" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
        )
        result <- store.getActivityRecordMeta()
      } yield {
        result shouldBe defined
        result.value.codeVersion shouldBe 1
        result.value.userVersion shouldBe 0
        result.value.startedIngestingAt shouldBe 1000000L
      }
    }

    "return updated values after update" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
        )
        _ <- store.updateActivityRecordMeta(
          codeVersion = 2,
          userVersion = 1,
          startedIngestingAt = 2000000L,
          earliestIngestedRound = 0L,
        )
        result <- store.getActivityRecordMeta()
      } yield {
        result shouldBe defined
        result.value.codeVersion shouldBe 2
        result.value.userVersion shouldBe 1
        result.value.startedIngestingAt shouldBe 2000000L
      }
    }

    "isolate meta rows by history_id" in {
      for {
        (store1, _) <- newStore()
        (store2, _) <- newStore()
        _ <- store1.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
        )
        _ <- store2.insertActivityRecordMeta(
          codeVersion = 5,
          userVersion = 3,
          startedIngestingAt = 9000000L,
          earliestIngestedRound = 0L,
        )
        result1 <- store1.getActivityRecordMeta()
        result2 <- store2.getActivityRecordMeta()
      } yield {
        result1.value.codeVersion shouldBe 1
        result1.value.userVersion shouldBe 0
        result2.value.codeVersion shouldBe 5
        result2.value.userVersion shouldBe 3
      }
    }

    "not affect other history_id on update" in {
      for {
        (store1, _) <- newStore()
        (store2, _) <- newStore()
        _ <- store1.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
        )
        _ <- store2.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
        )
        _ <- store1.updateActivityRecordMeta(
          codeVersion = 99,
          userVersion = 99,
          startedIngestingAt = 9999999L,
          earliestIngestedRound = 0L,
        )
        result2 <- store2.getActivityRecordMeta()
      } yield {
        result2.value.codeVersion shouldBe 1
        result2.value.userVersion shouldBe 0
        result2.value.startedIngestingAt shouldBe 1000000L
      }
    }
  }

  "ActivityIngestionMetaCheck" should {

    "insert meta on first call and cache on second" in {
      for {
        (store, _) <- newStore()
        check = new ActivityIngestionMetaCheck(store, 1, 0, loggerFactory)
        r1 <- check.ensure(1000000L, 10L)
        r2 <- check.ensure(2000000L, 20L)
        meta <- store.getActivityRecordMeta()
      } yield {
        r1 shouldBe InsertMeta
        r2 shouldBe Resume
        meta.value.startedIngestingAt shouldBe 1000000L
        meta.value.earliestIngestedRound shouldBe 10L
      }
    }

    "return Resume when versions match existing meta" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(1, 0, 1000000L, 10L)
        check = new ActivityIngestionMetaCheck(store, 1, 0, loggerFactory)
        result <- check.ensure(2000000L, 20L)
        meta <- store.getActivityRecordMeta()
      } yield {
        result shouldBe Resume
        meta.value.startedIngestingAt shouldBe 1000000L
        meta.value.earliestIngestedRound shouldBe 10L
      }
    }

    "return UpgradeMeta and update the row on version bump" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(1, 0, 1000000L, 10L)
        check = new ActivityIngestionMetaCheck(store, 2, 0, loggerFactory)
        result <- check.ensure(2000000L, 20L)
        meta <- store.getActivityRecordMeta()
      } yield {
        result shouldBe UpgradeMeta
        meta.value.codeVersion shouldBe 2
        meta.value.startedIngestingAt shouldBe 2000000L
        meta.value.earliestIngestedRound shouldBe 20L
      }
    }

    "return DowngradeDetected without modifying the row" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(2, 0, 1000000L, 10L)
        check = new ActivityIngestionMetaCheck(store, 1, 0, loggerFactory)
        result <- check.ensure(2000000L, 20L)
        meta <- store.getActivityRecordMeta()
      } yield {
        result shouldBe DowngradeDetected(1, 0, 2, 0)
        meta.value.codeVersion shouldBe 2
        meta.value.startedIngestingAt shouldBe 1000000L
        meta.value.earliestIngestedRound shouldBe 10L
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
        // max_round=43, 43-1=42 has records, so latest complete is 42
        result.value shouldBe 42L
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
        // max_round=12, 12-1=11 has records, so latest complete is 11
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
