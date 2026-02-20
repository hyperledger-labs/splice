package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.{
  AppActivityT,
  AppActivityRecordT,
}
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
  private val synchronizerId = SynchronizerId.tryFromString("activity-test::synchronizer")

  "DbAppActivityRecordStore" should {

    "insert app activity records" in {
      for {
        store <- newStore()
        ts1 = CantonTimestamp.now()

        record = AppActivityRecordT(
          migrationId = migrationId,
          recordTime = ts1,
          roundNumber = 42L,
          activities = Seq(
            AppActivityT(partyId = "app1::provider", weight = 100L),
            AppActivityT(partyId = "app2::provider", weight = 50L),
          ),
        )

        maxBefore <- maxRecordTime(migrationId)
        _ <- store.insertAppActivityRecords(Seq(record))
        maxAfter <- maxRecordTime(migrationId)
      } yield {
        maxBefore shouldBe None
        maxAfter shouldBe Some(ts1)
      }
    }

    "not insert duplicate app activity records (same record_time)" in {
      for {
        store <- newStore()
        ts1 = CantonTimestamp.now()
        ts2 = ts1.plusSeconds(1)

        record1 = mkRecord(ts1, 10L)
        record2 = mkRecord(ts2, 20L)

        // Insert first batch
        _ <- store.insertAppActivityRecords(Seq(record1))
        maxAfterFirst <- maxRecordTime(migrationId)

        // Try to insert batch with duplicate ts1 and new ts2
        _ <- store.insertAppActivityRecords(
          Seq(
            mkRecord(ts1, 99L), // should be skipped (same record_time)
            record2, // should be inserted
          )
        )

        maxAfterSecond <- maxRecordTime(migrationId)
      } yield {
        maxAfterFirst shouldBe Some(ts1)
        maxAfterSecond shouldBe Some(ts2)
      }
    }

    "batch insert multiple app activity records efficiently" in {
      for {
        store <- newStore()
        baseTs = CantonTimestamp.now()

        records = (0 until 50).map { i =>
          mkRecord(
            baseTs.plusSeconds(i.toLong),
            i.toLong,
            activities = Seq(AppActivityT(s"app$i::provider", i.toLong * 10)),
          )
        }

        _ <- store.insertAppActivityRecords(records)
        maxAfter <- maxRecordTime(migrationId)
      } yield {
        maxAfter shouldBe Some(baseTs.plusSeconds(49))
      }
    }

    "handle empty activities" in {
      for {
        store <- newStore()
        ts = CantonTimestamp.now()

        countBefore <- countRecords()
        record = mkRecord(ts, 100L, activities = Seq.empty)

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
      activities: Seq[AppActivityT] = Seq(AppActivityT("default::app", 100L)),
  ): AppActivityRecordT =
    AppActivityRecordT(
      migrationId = migrationId,
      recordTime = recordTime,
      roundNumber = roundNumber,
      activities = activities,
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
        synchronizerId,
        loggerFactory,
      )
    }
  }

  /** Test helper to query maxRecordTime directly from database */
  private def maxRecordTime(migrationId: Long): Future[Option[CantonTimestamp]] = {
    import storage.api.jdbcProfile.api.*
    futureUnlessShutdownToFuture(
      storage
        .query(
          sql"""
          select max(record_time)
          from app_activity_record_store
          where migration_id = $migrationId
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
