package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.DbSequencerTrafficSummaryStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbSequencerTrafficSummaryStore.{
  EnvelopeT,
  TrafficSummaryT,
}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, StoreTestBase, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.daml.metrics.api.noop.NoOpMetricsFactory

import scala.concurrent.Future

class DbSequencerTrafficSummaryStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  private val migrationId = 0L

  "DbSequencerTrafficSummaryStore" should {

    "insert traffic summaries" in {
      for {
        store <- newStore()
        ts1 = CantonTimestamp.now()

        summary = TrafficSummaryT(
          migrationId = migrationId,
          sequencingTime = ts1,
          totalTrafficCost = 100L,
          envelopes = Seq(
            EnvelopeT(trafficCost = 60L, viewIds = Seq(1, 2)),
            EnvelopeT(trafficCost = 40L, viewIds = Seq(3)),
          ),
        )

        maxBefore <- maxSequencingTime(migrationId)
        _ <- store.insertTrafficSummaries(Seq(summary))
        maxAfter <- maxSequencingTime(migrationId)
      } yield {
        maxBefore shouldBe None
        maxAfter shouldBe Some(ts1)
      }
    }

    "batch insert multiple traffic summaries efficiently" in {
      for {
        store <- newStore()
        baseTs = CantonTimestamp.now()

        summaries = (0 until 50).map { i =>
          mkSummary(
            baseTs.plusSeconds(i.toLong),
            (i * 10).toLong,
            envelopes = Seq(EnvelopeT(i.toLong, Seq(i))),
          )
        }

        _ <- store.insertTrafficSummaries(summaries)
        maxAfter <- maxSequencingTime(migrationId)
      } yield {
        maxAfter shouldBe Some(baseTs.plusSeconds(49))
      }
    }

    "handle empty envelopes" in {
      for {
        store <- newStore()
        ts = CantonTimestamp.now()

        summary = mkSummary(ts, 100L, envelopes = Seq.empty)

        _ <- store.insertTrafficSummaries(Seq(summary))
        maxAfter <- maxSequencingTime(migrationId)
      } yield {
        maxAfter shouldBe Some(ts)
      }
    }
  }

  private def mkSummary(
      sequencingTime: CantonTimestamp,
      totalTrafficCost: Long,
      envelopes: Seq[EnvelopeT],
  ): TrafficSummaryT =
    TrafficSummaryT(
      migrationId = migrationId,
      sequencingTime = sequencingTime,
      totalTrafficCost = totalTrafficCost,
      envelopes = envelopes,
    )

  private def newStore(): Future[DbSequencerTrafficSummaryStore] = {
    val participantId = mkParticipantId("traffic-test")
    val updateHistory = new UpdateHistory(
      storage.underlying,
      new DomainMigrationInfo(migrationId, None),
      "traffic_summary_test",
      participantId,
      dsoParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = false,
      HistoryMetrics(NoOpMetricsFactory, migrationId),
    )
    updateHistory.ingestionSink.initialize().map { _ =>
      new DbSequencerTrafficSummaryStore(
        storage.underlying,
        updateHistory,
        loggerFactory,
      )
    }
  }

  /** Test helper to query maxSequencingTime directly from database */
  private def maxSequencingTime(migrationId: Long): Future[Option[CantonTimestamp]] = {
    import storage.api.jdbcProfile.api.*
    futureUnlessShutdownToFuture(
      storage
        .query(
          sql"""
          select max(sequencing_time)
          from sequencer_traffic_summary_store
          where migration_id = $migrationId
        """.as[Option[CantonTimestamp]].head,
          "test.maxSequencingTime",
        )
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}
