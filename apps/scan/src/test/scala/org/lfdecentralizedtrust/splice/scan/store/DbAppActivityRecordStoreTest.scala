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
        store <- newStore()
        ts1 = CantonTimestamp.now()

        record = AppActivityRecordT(
          recordTime = ts1,
          roundNumber = roundNumber,
          appProviderParties = Seq("app1::provider", "app2::provider"),
          appActivityWeights = Seq(100L, 50L),
        )

        maxBefore <- maxRecordTime(roundNumber)
        _ <- store.insertAppActivityRecords(Seq(record))
        maxAfter <- maxRecordTime(roundNumber)
        loaded <- store.getRecordByRecordTime(ts1)
      } yield {
        maxBefore shouldBe None
        maxAfter shouldBe Some(ts1)
        // Verify the row decoders return the inserted data
        loaded.value shouldBe record
      }
    }

    "batch insert multiple app activity records efficiently" in {
      for {
        store <- newStore()
        baseTs = CantonTimestamp.now()

        records = (0 until 50).map { i =>
          mkRecord(
            baseTs.plusSeconds(i.toLong),
            roundNumber + i.toLong,
            appProviderParties = Seq(s"app$i::provider"),
            appActivityWeights = Seq(i.toLong * 10),
          )
        }

        _ <- store.insertAppActivityRecords(records)
        maxAfter <- maxRecordTime(roundNumber + 49)
        // Spot-check first, last and a middle record via row decoders
        first <- store.getRecordByRecordTime(baseTs)
        middle <- store.getRecordByRecordTime(baseTs.plusSeconds(25))
        last <- store.getRecordByRecordTime(baseTs.plusSeconds(49))
      } yield {
        maxAfter shouldBe Some(baseTs.plusSeconds(49))
        first.value shouldBe records(0)
        middle.value shouldBe records(25)
        last.value shouldBe records(49)
      }
    }

    "handle empty activities" in {
      for {
        store <- newStore()
        ts = CantonTimestamp.now()

        countBefore <- countRecords()
        record = mkRecord(ts, 100L, appProviderParties = Seq.empty, appActivityWeights = Seq.empty)

        _ <- store.insertAppActivityRecords(Seq(record))
        countAfter <- countRecords()
      } yield {
        countAfter shouldBe (countBefore + 1)
      }
    }
  }

  private def mkRecord(
      recordTime: CantonTimestamp,
      roundNumber: Long,
      appProviderParties: Seq[String],
      appActivityWeights: Seq[Long],
  ): AppActivityRecordT =
    AppActivityRecordT(
      recordTime = recordTime,
      roundNumber = roundNumber,
      appProviderParties = appProviderParties,
      appActivityWeights = appActivityWeights,
    )

  private def newStore(): Future[DbAppActivityRecordStore] = {
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
      new DbAppActivityRecordStore(
        storage.underlying,
        updateHistory,
        loggerFactory,
      )
    }
  }

  /** Test helper to query maxRecordTime directly from database */
  private def maxRecordTime(roundNumber: Long): Future[Option[CantonTimestamp]] = {
    import storage.api.jdbcProfile.api.*
    futureUnlessShutdownToFuture(
      storage
        .query(
          sql"""
          select max(record_time)
          from app_activity_record_store
          where round_number = $roundNumber
        """.as[Option[CantonTimestamp]].head,
          "test.maxRecordTime",
        )
    )
  }

  /** Test helper to count records in the database */
  private def countRecords(): Future[Long] = {
    import storage.api.jdbcProfile.api.*
    futureUnlessShutdownToFuture(
      storage
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
