package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.scan.store.db.DbSequencerTrafficSummaryStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbSequencerTrafficSummaryStore.{
  EnvelopeT,
  TrafficSummaryT,
}
import org.lfdecentralizedtrust.splice.store.{StoreTestBase, TimestampWithMigrationId}
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest

import scala.concurrent.Future

class DbSequencerTrafficSummaryStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  private val migrationId = 0L
  private val synchronizerId = SynchronizerId.tryFromString("traffic-test::synchronizer")

  "DbSequencerTrafficSummaryStore" should {

    "insert and retrieve traffic summaries with envelopes" in {
      for {
        store <- newStore()
        ts1 = CantonTimestamp.now()

        summary = TrafficSummaryT(
          rowId = 0L,
          migrationId = migrationId,
          sequencingTime = ts1,
          totalTrafficCost = 100L,
          envelopes = Seq(
            EnvelopeT(trafficCost = 60L, viewHashes = Seq("hash1", "hash2")),
            EnvelopeT(trafficCost = 40L, viewHashes = Seq("hash3")),
          ),
        )

        _ <- store.insertTrafficSummaries(Seq(summary))
        results <- store.listTrafficSummaries(None, limit = 10)
      } yield {
        results.size shouldBe 1
        val retrieved = results.head
        retrieved.sequencingTime shouldBe ts1
        retrieved.totalTrafficCost shouldBe 100L
        retrieved.envelopes.size shouldBe 2
        retrieved.envelopes.head.trafficCost shouldBe 60L
        retrieved.envelopes.head.viewHashes shouldBe Seq("hash1", "hash2")
        retrieved.envelopes(1).trafficCost shouldBe 40L
        retrieved.envelopes(1).viewHashes shouldBe Seq("hash3")
      }
    }

    "not insert duplicate traffic summaries (same sequencing_time)" in {
      for {
        store <- newStore()
        ts1 = CantonTimestamp.now()
        ts2 = ts1.plusSeconds(1)

        summary1 = mkSummary(ts1, 100L)
        summary2 = mkSummary(ts2, 200L)

        // Insert first batch
        _ <- store.insertTrafficSummaries(Seq(summary1))

        // Try to insert batch with duplicate ts1 and new ts2
        _ <- store.insertTrafficSummaries(
          Seq(
            mkSummary(ts1, 999L), // should be skipped (same sequencing_time)
            summary2, // should be inserted
          )
        )

        results <- store.listTrafficSummaries(None, limit = 10)
      } yield {
        results.size shouldBe 2
        // Verify the original ts1 entry wasn't overwritten
        results.find(_.sequencingTime == ts1).value.totalTrafficCost shouldBe 100L
        results.find(_.sequencingTime == ts2).value.totalTrafficCost shouldBe 200L
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
            envelopes = Seq(EnvelopeT(i.toLong, Seq(s"hash-$i"))),
          )
        }

        _ <- store.insertTrafficSummaries(summaries)
        results <- store.listTrafficSummaries(None, limit = 100)
      } yield {
        results.size shouldBe 50
        // Verify ordering by sequencing_time
        results.map(_.sequencingTime) shouldBe results.map(_.sequencingTime).sorted
      }
    }

    "paginate results correctly" in {
      for {
        store <- newStore()
        baseTs = CantonTimestamp.now()

        summaries = (0 until 10).map { i =>
          mkSummary(baseTs.plusSeconds(i.toLong), (i * 10).toLong)
        }

        _ <- store.insertTrafficSummaries(summaries)

        // First page
        page1 <- store.listTrafficSummaries(None, limit = 3)

        // Second page using cursor from last item
        lastOfPage1 = page1.last
        cursor = TimestampWithMigrationId(lastOfPage1.sequencingTime, lastOfPage1.migrationId)
        page2 <- store.listTrafficSummaries(Some(cursor), limit = 3)

        // Third page
        lastOfPage2 = page2.last
        cursor2 = TimestampWithMigrationId(lastOfPage2.sequencingTime, lastOfPage2.migrationId)
        page3 <- store.listTrafficSummaries(Some(cursor2), limit = 3)
      } yield {
        page1.size shouldBe 3
        page2.size shouldBe 3
        page3.size shouldBe 3

        // No overlap between pages
        val allIds = (page1 ++ page2 ++ page3).map(_.sequencingTime)
        allIds.distinct.size shouldBe allIds.size

        // Pages are in order
        page1.last.sequencingTime should be < page2.head.sequencingTime
        page2.last.sequencingTime should be < page3.head.sequencingTime
      }
    }

    "return correct maxSequencingTime" in {
      for {
        store <- newStore()
        baseTs = CantonTimestamp.now()

        // Initially empty
        maxBefore <- store.maxSequencingTime(migrationId)

        summaries = Seq(
          mkSummary(baseTs, 10L),
          mkSummary(baseTs.plusSeconds(5), 20L),
          mkSummary(baseTs.plusSeconds(2), 30L),
        )

        _ <- store.insertTrafficSummaries(summaries)
        maxAfter <- store.maxSequencingTime(migrationId)
      } yield {
        maxBefore shouldBe None
        maxAfter shouldBe Some(baseTs.plusSeconds(5))
      }
    }

    "handle empty envelopes" in {
      for {
        store <- newStore()
        ts = CantonTimestamp.now()

        summary = mkSummary(ts, 100L, envelopes = Seq.empty)

        _ <- store.insertTrafficSummaries(Seq(summary))
        results <- store.listTrafficSummaries(None, limit = 10)
      } yield {
        results.size shouldBe 1
        results.head.envelopes shouldBe empty
      }
    }

    "preserve envelope ordering" in {
      for {
        store <- newStore()
        ts = CantonTimestamp.now()

        envelopes = (0 until 5).map { i =>
          EnvelopeT(trafficCost = i.toLong, viewHashes = Seq(s"hash-$i"))
        }
        summary = mkSummary(ts, 100L, envelopes = envelopes)

        _ <- store.insertTrafficSummaries(Seq(summary))
        results <- store.listTrafficSummaries(None, limit = 10)
      } yield {
        results.size shouldBe 1
        val retrievedEnvelopes = results.head.envelopes
        retrievedEnvelopes.size shouldBe 5
        // Verify ordering preserved
        retrievedEnvelopes.map(_.trafficCost) shouldBe Seq(0L, 1L, 2L, 3L, 4L)
      }
    }
  }

  private def mkSummary(
      sequencingTime: CantonTimestamp,
      totalTrafficCost: Long,
      envelopes: Seq[EnvelopeT] = Seq(EnvelopeT(10L, Seq("default-hash"))),
  ): TrafficSummaryT =
    TrafficSummaryT(
      rowId = 0L,
      migrationId = migrationId,
      sequencingTime = sequencingTime,
      totalTrafficCost = totalTrafficCost,
      envelopes = envelopes,
    )

  private def newStore(): Future[DbSequencerTrafficSummaryStore] = {
    DbSequencerTrafficSummaryStore(
      storage.underlying,
      dsoParty,
      mkParticipantId("traffic-test"),
      synchronizerId,
      loggerFactory,
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
}
