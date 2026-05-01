// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import cats.data.OptionT
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
import io.grpc.StatusRuntimeException
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider, SpliceMetrics}
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
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.math.Ordering.Implicits.*
import scala.util.Using
import io.circe.Decoder

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
    zstdCompressionLevel = 3,
  )
  val appConfig = BulkStorageConfig(
    updatesPollingInterval = NonNegativeFiniteDuration.ofSeconds(5)
  )

  "UpdateHistoryBulkStorage" should {

    "successfully dump a single segment of updates to an s3 bucket" in {
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
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
        .toMat(TestSink.probe[Seq[String]])(Keep.right)
        .run()

      probe.request(1)

      clue(
        "Initially, 1000 updates will be ready, but the segment will not be complete, so no output is expected"
      ) {
        probe.expectNoMessage(20.seconds)
      }

      clue(
        "Ingest 1000 more events. Now the last timestamp will be beyond the segment, so the source will complete and emit the object keys"
      ) {
        mockStore.mockIngestion(1000)
        probe.expectNext(20.seconds) should contain theSameElementsInOrderAs Seq(
          "1970-01-01T00:00:00.100Z~1970-01-01T00:00:02.300Z/updates_0.zstd",
          "1970-01-01T00:00:00.100Z~1970-01-01T00:00:02.300Z/updates_1.zstd",
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
          val objectKeys = s3Objects.contents.asScala.map(_.key()).sorted
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
          /* We hard-code the expected digests to enforce that the persisted data format does not change.
             These values must not be modified unless there is a conscious decision to change the persisted format,
             with a migration plan for how to apply it consistently across SVs. */
          bucketConnection
            .getChecksums(objectKeys.toSeq)
            .futureValue
            .map(_.checksum) should contain theSameElementsInOrderAs Seq(
            "MM+DyxPP6UgpAaSCsm99j4ZAtYIK3TIrPmxFyodBrQQ=",
            "2oWb5Um18xwnJTMkC4yilyrcsUADYoxtV7toJi29VsI=",
          )
        }
      }
    }

    "successfully handle an empty segment" in {
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
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
            .toMat(TestSink.probe[Seq[String]])(Keep.right)
            .run()
          probe.request(1)
          probe.expectNext(20.seconds) should be(empty)
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
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
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

    "list objects correctly" in {
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
      val mockKvStore = mock[KeyValueStore]
      when(
        mockKvStore.readValueAndLogOnDecodingFailure[UpdatesSegment](
          eqTo("latest_updates_segment_in_bulk_storage")
        )(
          any[Decoder[UpdatesSegment]],
          any[TraceContext],
          any[ExecutionContext],
        )
      ).thenReturn(
        OptionT[Future, UpdatesSegment](
          Future(
            Some(
              UpdatesSegment(
                TimestampWithMigrationId(
                  CantonTimestamp.tryFromInstant(Instant.parse("2015-10-23T00:00:00Z")),
                  1L,
                ),
                TimestampWithMigrationId(
                  CantonTimestamp.tryFromInstant(Instant.parse("2015-10-24T00:00:00Z")),
                  1L,
                ),
              )
            )
          )
        )
      )
      val mockKvProvider = new ScanKeyValueProvider(mockKvStore, loggerFactory)
      val svc = new UpdateHistoryBulkStorage(
        bulkStorageTestConfig,
        appConfig,
        mock[UpdateHistory],
        mockKvProvider,
        0L,
        bucketConnection,
        new HistoryMetrics(new InMemoryMetricsFactory)(MetricsContext.Empty),
        loggerFactory,
      )

      val d20u0 = "2015-10-20T00:00:00Z~2015-10-21T00:00:00Z/updates_0.zstd"
      val d20u1 = "2015-10-20T00:00:00Z~2015-10-21T00:00:00Z/updates_1.zstd"
      val d21u0 = "2015-10-21T00:00:00Z~2015-10-22T00:00:00Z/updates_0.zstd"
      val d21u1 = "2015-10-21T00:00:00Z~2015-10-22T00:00:00Z/updates_1.zstd"
      val d22u0 = "2015-10-22T00:00:00Z~2015-10-23T00:00:00Z/updates_0.zstd"
      val d22u1 = "2015-10-22T00:00:00Z~2015-10-23T00:00:00Z/updates_1.zstd"
      val d23u0 = "2015-10-23T00:00:00Z~2015-10-24T00:00:00Z/updates_0.zstd"
      val d23u1 = "2015-10-23T00:00:00Z~2015-10-24T00:00:00Z/updates_1.zstd"
      val d24u0 = "2015-10-24T00:00:00Z~2015-10-25T00:00:00Z/updates_0.zstd"
      val d24u1 = "2015-10-24T00:00:00Z~2015-10-25T00:00:00Z/updates_1.zstd"
      val allObjs = Seq(
        d20u0,
        d20u1,
        d21u0,
        d21u1,
        d22u0,
        d22u1,
        d23u0,
        d23u1,
        d24u0,
        d24u1,
      )
      Future
        .sequence(allObjs.map {
          bucketConnection.createObject(_)
        })
        .futureValue

      // A wider range than the data
      val res1 = svc
        .getUpdatesBetweenDates(
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-10T00:00:00Z")),
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-30T00:00:00Z")),
          PageLimit.tryCreate(10),
          None,
        )
        .futureValue
      res1.objects.map(_.key) should contain theSameElementsInOrderAs Seq(
        d20u0,
        d20u1,
        d21u0,
        d21u1,
        d22u0,
        d22u1,
        d23u0,
        d23u1,
      )
      res1.nextPageTokenO shouldBe Some("2015-10-23T00:00:00Z~2015-10-24T00:00:00Z/")
      val res1b = svc
        .getUpdatesBetweenDates(
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-10T00:00:00Z")),
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-30T00:00:00Z")),
          PageLimit.tryCreate(10),
          res1.nextPageTokenO,
        )
        .futureValue
      res1b.objects.map(_.key) shouldBe empty
      res1b.nextPageTokenO shouldBe Some("2015-10-23T00:00:00Z~2015-10-24T00:00:00Z/")

      // A smaller range within the data
      val res2 = svc
        .getUpdatesBetweenDates(
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-21T16:00:00Z")),
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-21T16:00:05Z")),
          PageLimit.tryCreate(10),
          None,
        )
        .futureValue
      res2.objects.map(_.key) should contain theSameElementsInOrderAs Seq(d21u0, d21u1)
      res2.nextPageTokenO shouldBe None

      // pagination
      val res3 = svc
        .getUpdatesBetweenDates(
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-01T12:00:00Z")),
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-21T16:00:05Z")),
          PageLimit.tryCreate(
            3
          ), // on purpose 3 even though we expect only 2 back (since the response is always full days of updates)
          None,
        )
        .futureValue
      res3.objects.map(_.key) should contain theSameElementsInOrderAs Seq(d20u0, d20u1)
      res3.nextPageTokenO shouldBe Some("2015-10-20T00:00:00Z~2015-10-21T00:00:00Z/")
      val res3b = svc
        .getUpdatesBetweenDates(
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-01T12:00:00Z")),
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-21T16:00:05Z")),
          PageLimit.tryCreate(3),
          res3.nextPageTokenO,
        )
        .futureValue
      res3b.objects.map(_.key) should contain theSameElementsInOrderAs Seq(d21u0, d21u1)
      res3b.nextPageTokenO shouldBe None

      // exact match with start and end of segments
      val res4 = svc
        .getUpdatesBetweenDates(
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-21T00:00:00Z")),
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-23T00:00:00Z")),
          PageLimit.tryCreate(4),
          None,
        )
        .futureValue
      res4.objects
        .map(_.key) should contain theSameElementsInOrderAs Seq(d21u0, d21u1, d22u0, d22u1)
      res4.nextPageTokenO shouldBe None

      // limit too low for first folder
      val ex = svc
        .getUpdatesBetweenDates(
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-21T00:00:00Z")),
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-23T00:00:00Z")),
          PageLimit.tryCreate(1),
          None,
        )
        .failed
        .futureValue
      ex shouldBe a[StatusRuntimeException]
      ex.asInstanceOf[StatusRuntimeException]
        .getStatus
        .getCode shouldBe io.grpc.Status.Code.INVALID_ARGUMENT

      // Test handling an empty segment: Simulate no updates in 2015-10-25 to 2015-10-26
      val d26u0 = "2015-10-26T00:00:00Z~2015-10-27T00:00:00Z/updates_0.zstd"
      val d26u1 = "2015-10-26T00:00:00Z~2015-10-27T00:00:00Z/updates_1.zstd"
      val moreObjs = Seq(
        "2015-10-25T00:00:00Z~2015-10-26T00:00:00Z/ACS_0.zstd",
        d26u0,
        d26u1,
      )
      Future
        .sequence(moreObjs.map {
          bucketConnection.createObject(_)
        })
        .futureValue
      // Update the kvStore mock to report that up to 10-27 everything was dumped
      when(
        mockKvStore.readValueAndLogOnDecodingFailure[UpdatesSegment](
          eqTo("latest_updates_segment_in_bulk_storage")
        )(
          any[Decoder[UpdatesSegment]],
          any[TraceContext],
          any[ExecutionContext],
        )
      ).thenReturn(
        OptionT[Future, UpdatesSegment](
          Future(
            Some(
              UpdatesSegment(
                TimestampWithMigrationId(
                  CantonTimestamp.tryFromInstant(Instant.parse("2015-10-26T00:00:00Z")),
                  1L,
                ),
                TimestampWithMigrationId(
                  CantonTimestamp.tryFromInstant(Instant.parse("2015-10-27T00:00:00Z")),
                  1L,
                ),
              )
            )
          )
        )
      )
      // Query up to the middle of the empty segment
      val res5 = svc
        .getUpdatesBetweenDates(
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-20T00:00:00Z")),
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-25T12:00:00Z")),
          PageLimit.tryCreate(20),
          None,
        )
        .futureValue
      // First response contains all data, but with a next page token
      res5.objects.map(_.key) should contain theSameElementsInOrderAs allObjs
      res5.nextPageTokenO shouldBe Some("2015-10-24T00:00:00Z~2015-10-25T00:00:00Z/")
      val res5b = svc
        .getUpdatesBetweenDates(
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-21T00:00:00Z")),
          CantonTimestamp.tryFromInstant(Instant.parse("2015-10-25T12:00:00Z")),
          PageLimit.tryCreate(20),
          res5.nextPageTokenO,
        )
        .futureValue
      // Second page should be empty, with no nextPageToken
      res5b.objects.map(_.key) shouldBe empty
      res5b.nextPageTokenO shouldBe None
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
        version = DarResources.amulet_0_1_17,
      )
      val tx = mkCreateTx(
        1, // not used in updates v2
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
