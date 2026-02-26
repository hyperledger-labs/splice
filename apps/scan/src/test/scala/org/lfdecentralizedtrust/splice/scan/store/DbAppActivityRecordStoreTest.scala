package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.AppActivityRecordT
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
        first <- store.getRecordByRecordTime(baseTs)
        middle <- store.getRecordByRecordTime(baseTs.plusSeconds(25))
        last <- store.getRecordByRecordTime(baseTs.plusSeconds(49))
        // Batch fetch a subset of records
        batchTimes = Seq(baseTs, baseTs.plusSeconds(10), baseTs.plusSeconds(49))
        batchResult <- store.getRecordsByRecordTimes(batchTimes)
        // Batch fetch with empty input
        emptyResult <- store.getRecordsByRecordTimes(Seq.empty)
        // Batch fetch with a non-existent time mixed in
        missingTs = baseTs.plusSeconds(999)
        partialResult <- store.getRecordsByRecordTimes(Seq(baseTs, missingTs))
      } yield {
        first.value shouldBe records(0)
        middle.value shouldBe records(25)
        last.value shouldBe records(49)
        // Batch assertions
        batchResult should have size 3
        batchResult(baseTs) shouldBe records(0)
        batchResult(baseTs.plusSeconds(10)) shouldBe records(10)
        batchResult(baseTs.plusSeconds(49)) shouldBe records(49)
        emptyResult shouldBe empty
        partialResult should have size 1
        partialResult(baseTs) shouldBe records(0)
        partialResult.get(missingTs) shouldBe None
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

  /** Creates a new store and returns it along with a unique history_id
    * obtained from UpdateHistory initialization.
    */
  private def newStore(): Future[(DbAppActivityRecordStore, Long)] = {
    val participantId = mkParticipantId("activity-test")
    val updateHistory = new UpdateHistory(
      storage.underlying,
      new DomainMigrationInfo(migrationId, None),
      "app_activity_test",
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
        loggerFactory,
      )
      (store, updateHistory.historyId)
    }
  }

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
