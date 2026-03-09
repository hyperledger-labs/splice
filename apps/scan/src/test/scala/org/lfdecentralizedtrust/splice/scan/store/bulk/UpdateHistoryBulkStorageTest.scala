// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.ledger.javaapi.data as javaApi
import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.metrics.api.testing.InMemoryMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.WallClock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, SpliceMetrics}
import org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{ScanKeyValueProvider, ScanKeyValueStore}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.slf4j.event.Level

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.math.Ordering.Implicits.*
import java.nio.ByteBuffer
import scala.util.Using

class UpdateHistoryBulkStorageTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest {
  val maxFileSize = 25000L
  val bulkStorageTestConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 1,
    bulkAcsSnapshotPeriodHours = 2,
    bulkDbReadChunkSize = 500,
    bulkZstdFrameSize = 10000L,
    maxFileSize,
  )
  val appConfig = BulkStorageConfig(
    updatesPollingInterval = NonNegativeFiniteDuration.ofSeconds(5)
  )

  "UpdateHistoryBulkStorage" should {

    "multipart upload works" in {
      val bucketConnection = S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
      val o = bucketConnection.newAppendWriteObject("test")
      o.prepareUploadNext()
      o.prepareUploadNext()
      for {
        _ <- o.upload(1, ByteBuffer.wrap("hello".getBytes("UTF-8")))
        _ <- o.upload(2, ByteBuffer.wrap("world".getBytes("UTF-8")))
        _ <- o.finish()
        content <- bucketConnection.readFullObject("test")
      } yield {
        new String(content.array(), "UTF-8") shouldBe "helloworld"
      }
    }

    "successfully dump a single segment of updates to an s3 bucket" in {
      val bucketConnection = S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
      val initialStoreSize = 1500
      val segmentSize = 2200L
      val segmentFromTimestamp = 100L
      val mockStore =
        new MockUpdateHistoryStore(initialStoreSize, Instant.ofEpochMilli)
      val fromTimestamp =
        CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(segmentFromTimestamp))
      val toTimestamp =
        CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(segmentFromTimestamp + segmentSize))

      val segment = UpdatesSegment(
        TimestampWithMigrationId(fromTimestamp, 0),
        TimestampWithMigrationId(toTimestamp, 0),
      )
      val metricsFactory = new InMemoryMetricsFactory
      val probe = UpdateHistorySegmentBulkStorage
        .asSource(
          bulkStorageTestConfig,
          appConfig,
          mockStore.store,
          bucketConnection,
          segment,
          new HistoryMetrics(metricsFactory)(MetricsContext.Empty),
          loggerFactory,
        )
        .toMat(TestSink.probe[UpdateHistorySegmentBulkStorage.Output])(Keep.right)
        .run()

      probe.request(3)

      clue(
        "Initially, 1000 updates will be ready, but the segment will not be complete, so no output is expected"
      ) {
        probe.expectNoMessage(20.seconds)
      }

      clue(
        "Ingest 1000 more events. Now the last timestamp will be beyond the segment, so the source will complete and emit the last timestamp"
      ) {
        mockStore.mockIngestion(1000)
        probe.expectNext(20.seconds) should be(
          UpdateHistorySegmentBulkStorage.Output(
            segment,
            "1970-01-01T00:00:00.100Z-Migration-0-1970-01-01T00:00:02.300Z/updates_0.zstd",
            isLastObjectInSegment = false,
          )
        )
        probe.expectNext(20.seconds) should be(
          UpdateHistorySegmentBulkStorage.Output(
            segment,
            "1970-01-01T00:00:00.100Z-Migration-0-1970-01-01T00:00:02.300Z/updates_1.zstd",
            isLastObjectInSegment = true,
          )
        )
        probe.expectComplete()
        val objectCountMetrics = metricsFactory.metrics.counters
          .get(SpliceMetrics.MetricsPrefix :+ "history" :+ "bulk-storage" :+ "object-count")
          .value
        val numObjectsFromMetric = objectCountMetrics
          .get(MetricsContext.Empty)
          .value
          .markers
          .get(MetricsContext("object_type" -> "updates"))
          .value
          .get()
        numObjectsFromMetric shouldBe 2
      }

      clue("Check that the dumped content is correct") {
        for {
          s3Objects <- bucketConnection.listObjects
          allUpdates <- mockStore.store.getUpdatesWithoutImportUpdates(
            None,
            HardLimit.tryCreate(segmentSize.toInt * 2, segmentSize.toInt * 2),
          )
          segmentUpdates = allUpdates.filter(update =>
            update.update.update.recordTime > fromTimestamp &&
              update.update.update.recordTime <= toTimestamp
          )
        } yield {
          val objectKeys = s3Objects.contents.asScala.sortBy(_.key())
          objectKeys should have length 2
          s3Objects.contents().get(0).size().toInt should be >= maxFileSize.toInt
          val allUpdatesFromS3 = objectKeys.flatMap(
            readUncompressAndDecode(bucketConnection, io.circe.parser.decode[UpdateHistoryItemV2])
          )
          allUpdatesFromS3.length shouldBe segmentUpdates.length
          allUpdatesFromS3
            .map(
              new CompactJsonScanHttpEncodings(identity, identity).httpToLapiUpdate
            ) should contain theSameElementsInOrderAs segmentUpdates
        }
      }
    }

    "successfully handle an empty segment" in {
      val bucketConnection = S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
      val mockStore =
        new MockUpdateHistoryStore(10, { i => Instant.ofEpochMilli(i + 1000) })
      val fromTimestamp =
        CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(100L))
      val toTimestamp =
        CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(200L))

      val segment = UpdatesSegment(
        TimestampWithMigrationId(fromTimestamp, 0),
        TimestampWithMigrationId(toTimestamp, 0),
      )
      val metricsFactory = new InMemoryMetricsFactory

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.WARN))(
        {
          val probe = UpdateHistorySegmentBulkStorage
            .asSource(
              bulkStorageTestConfig,
              appConfig,
              mockStore.store,
              bucketConnection,
              segment,
              new HistoryMetrics(metricsFactory)(MetricsContext.Empty),
              loggerFactory,
            )
            .toMat(TestSink.probe[UpdateHistorySegmentBulkStorage.Output])(Keep.right)
            .run()
          probe.request(1)

          probe.expectComplete()
        },
        logEntries =>
          forExactly(1, logEntries)(logEntry =>
            logEntry.message should (include("No updates found in segment"))
          ),
      )

      succeed

    }

    "successfully dump all segments" in {
      val bucketConnection = S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
      val initialStoreSize = 2000
      val genesisDate = LocalDate.of(2001, 1, 23)
      val genesisInstant = genesisDate.atTime(2, 34).toInstant(ZoneOffset.UTC)
      val metricsFactory = new InMemoryMetricsFactory
      def latestSegmentMetrics = metricsFactory.metrics.gauges
        .get(
          SpliceMetrics.MetricsPrefix :+ "history" :+ "bulk-storage" :+ "latest-updates-segment"
        )
        .value

      val mockStore = new MockUpdateHistoryStore(
        initialStoreSize,
        i => genesisInstant.plusSeconds(i * 10),
      )
      val kvProvider = mkProvider.futureValue

      def newRetryProviderAndUpdatesBulkStorageService(migrationId: Long) = {

        val retryProvider =
          RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)

        val svc = new UpdateHistoryBulkStorage(
          bulkStorageTestConfig,
          appConfig,
          mockStore.store,
          kvProvider,
          migrationId,
          bucketConnection,
          new HistoryMetrics(metricsFactory)(MetricsContext.Empty),
          loggerFactory,
        ).asRetryableService(
          AutomationConfig(pollingInterval =
            NonNegativeFiniteDuration.ofSeconds(1)
          ), // Fast retries
          new WallClock(timeouts, loggerFactory),
          retryProvider,
        )

        (retryProvider, svc)
      }

      def assertLatestSegmentInDb(
          fromHour: Int,
          fromMigration: Int,
          toHour: Int,
          toMigration: Int,
      ) = {
        val segment = UpdatesSegment(
          TimestampWithMigrationId(
            CantonTimestamp.tryFromInstant(
              genesisDate.atTime(fromHour, 0).toInstant(ZoneOffset.UTC)
            ),
            fromMigration.toLong,
          ),
          TimestampWithMigrationId(
            CantonTimestamp.tryFromInstant(
              genesisDate.atTime(toHour, 0).toInstant(ZoneOffset.UTC)
            ),
            toMigration.toLong,
          ),
        )
        kvProvider.getLatestUpdatesSegmentInBulkStorage().value.futureValue.value shouldBe segment
      }

      def assertLatestSegmentInMetrics(hour: Int) =
        latestSegmentMetrics.get(MetricsContext.Empty).value.value.get()._1 shouldBe genesisDate
          .atTime(hour, 0)
          .toInstant(ZoneOffset.UTC)
          .toEpochMilli * 1000

      val (retryProvider, svc) = newRetryProviderAndUpdatesBulkStorageService(0L)
      Using.resources(
        svc,
        retryProvider,
      ) { (_, _) =>
        clue("First 2000 events end at 08:07:10, so expecting segments up to 08:00") {
          eventually() {
            assertLatestSegmentInDb(6, 0, 8, 0)

            assertLatestSegmentInMetrics(8)
          }
        }

        clue("Ingest 2000 more updates, up to 13:14, expecting segments up to 12:00") {
          mockStore.mockIngestion(2000)
          eventually() {
            assertLatestSegmentInDb(10, 0, 12, 0)
            assertLatestSegmentInMetrics(12)
          }

        }
      }

      // Now we simulate a migration: we kill the current pipeline (to simulate the scan app restarting),
      // then start a new one with the new migration and ingest updates in the new migration

      mockStore.mockMigration()
      val (retryProvider1, svc1) = newRetryProviderAndUpdatesBulkStorageService(1L)
      Using.resources(svc1, retryProvider1) { (_, _) =>
        clue("500 more updates in the new migration, up to 15:03") {
          mockStore.mockIngestion(500)
          eventually() {
            assertLatestSegmentInDb(12, 0, 14, 1)
            assertLatestSegmentInMetrics(14)
          }
        }
      }
    }
  }

  class MockUpdateHistoryStore(
      val initialStoreSize: Int,
      val idxToTimestamp: Long => Instant,
  ) {

    val store = mockUpdateHistoryStore()

    val alicePartyId = mkPartyId("alice")
    val bobPartyId = mkPartyId("bob")
    val charliePartyId = mkPartyId("charlie")

    private var data: Seq[TreeUpdateWithMigrationId] =
      Seq.range(0, initialStoreSize).map(_.toLong).map(genElement)
    private var currentMigration = 0

    def mockIngestion(extraUpdates: Int): Unit = {
      val curSize = data.size
      data = data ++ Seq.range(curSize, curSize + extraUpdates).map(_.toLong).map(genElement)
    }
    def mockMigration(): Unit = currentMigration = currentMigration + 1

    def mockUpdateHistoryStore(): UpdateHistory = {
      val store = mock[UpdateHistory]
      when(
        store.getUpdatesWithoutImportUpdates(
          any[Option[(Long, CantonTimestamp)]],
          any[Limit],
          any[String => Option[javaApi.Value]],
        )(any[TraceContext])
      ).thenAnswer {
        (
            afterO: Option[(Long, CantonTimestamp)],
            limit: Limit,
        ) =>
          val after = afterO
            .map(a => TimestampWithMigrationId(a._2, a._1))
            .getOrElse(TimestampWithMigrationId(CantonTimestamp.MinValue, 0L))
          Future.successful(
            data
              .filter(update =>
                TimestampWithMigrationId(
                  update.update.update.recordTime,
                  update.migrationId,
                ) > after
              )
              .take(limit.limit)
          )
      }
      when(
        store.getLowestMigrationForRecordTime(
          any[CantonTimestamp]
        )(any[TraceContext])
      ).thenAnswer { (recordTime: CantonTimestamp) =>
        Future.successful(
          data.filter(_.update.update.recordTime > recordTime).map(_.migrationId).minOption
        )
      }
      when(store.isReady).thenReturn(true)
      when(
        store.isHistoryBackfilled(anyLong)(any[TraceContext])
      ).thenReturn(Future.successful(true))
      store
    }

    private def genElement(idx: Long) = {
      val contract = amulet(
        alicePartyId,
        BigDecimal(idx),
        0L,
        BigDecimal(0.1),
        contractId = LfContractId.assertFromString("00" + f"$idx%064x").coid,
      )
      val tx = mkCreateTx(
        1, // not used in updates v2 (TODO(#3429): double-check what the actual value in the updateHistory is. The parser in read (httpToLapiTransaction) sets this to 1, so for now we use 1 here too.)
        Seq(contract),
        idxToTimestamp(idx),
        Seq(alicePartyId, bobPartyId),
        dummyDomain,
        "",
        idxToTimestamp(idx),
        Seq(charliePartyId),
        updateId = idx.toString,
      )
      new TreeUpdateWithMigrationId(
        UpdateHistoryResponse(TransactionTreeUpdate(tx), dummyDomain),
        currentMigration.toLong,
      )
    }
  }

  def mkProvider: Future[ScanKeyValueProvider] = {
    ScanKeyValueStore(
      dsoParty = dsoParty,
      participantId = mkParticipantId("participant"),
      storage,
      loggerFactory,
    ).map(new ScanKeyValueProvider(_, loggerFactory))
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
