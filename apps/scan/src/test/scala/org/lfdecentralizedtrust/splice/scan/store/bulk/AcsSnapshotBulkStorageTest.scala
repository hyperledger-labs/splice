// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

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
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import io.grpc.StatusRuntimeException
import org.apache.pekko.stream.scaladsl.Sink
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, SpliceMetrics}
import org.lfdecentralizedtrust.splice.http.v0.definitions as httpApi
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  ScanKeyValueProvider,
  ScanKeyValueStore,
}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotResult
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  HasS3Mock,
  HistoryMetrics,
  Limit,
  S3BucketConnection,
  StoreTestBase,
  TimestampWithMigrationId,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.util.PackageQualifiedName
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.slf4j.event.Level

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import scala.util.Using

class AcsSnapshotBulkStorageTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest {

  val acsSnapshotSize = 48500
  val bulkStorageTestConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 3,
    bulkAcsSnapshotPeriodHours = 24,
    bulkDbReadChunkSize = 1000,
    bulkZstdFrameSize = 10000L,
    bulkMaxFileSize = 50000L,
    zstdCompressionLevel = 3,
  )
  val appConfig = BulkStorageConfig(
    snapshotPollingInterval = NonNegativeFiniteDuration.ofSeconds(5)
  )

  "AcsSnapshotBulkStorage" should {
    "successfully dump a single ACS snapshot" in {
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
      val ts = CantonTimestamp.tryFromInstant(Instant.parse("2026-01-02T00:00:00Z"))
      val store = new MockAcsSnapshotStore(ts).store
      val metricsFactory = new InMemoryMetricsFactory
      for {
        _ <- SingleAcsSnapshotBulkStorage
          .asSource(
            TimestampWithMigrationId(ts, 0),
            bulkStorageTestConfig,
            appConfig,
            store,
            bucketConnection,
            new HistoryMetrics(metricsFactory)(MetricsContext.Empty),
            loggerFactory,
          )
          .runWith(Sink.ignore)

        s3Objects <- bucketConnection.listObjects
        allContracts <- store
          .queryAcsSnapshot(
            0,
            ts,
            None,
            HardLimit.tryCreate(acsSnapshotSize, acsSnapshotSize),
            Seq.empty,
            Seq.empty,
          )
          .map(_.createdEventsInPage)
      } yield {
        val objectKeys = s3Objects.contents.asScala.map(_.key()).sorted
        objectKeys should have length 7
        objectKeys.foreach(
          _ should startWith("2026-01-02T00:00:00Z-Migration-0-2026-01-03T00:00:00Z/ACS_")
        )
        val objectCountMetrics = metricsFactory.metrics.counters.get(
          SpliceMetrics.MetricsPrefix :+ "history" :+ "bulk-storage" :+ "object-count"
        )
        val numObjectsFromMetric = objectCountMetrics.value
          .get(MetricsContext.Empty)
          .value
          .markers
          .get(MetricsContext("object_type" -> "ACS_snapshots"))
          .value
          .get()
        numObjectsFromMetric shouldBe 7

        val allContractsFromS3 = objectKeys.flatMap(
          readUncompressAndDecode(
            bucketConnection,
            io.circe.parser.decode[httpApi.ActiveContract],
          )
        )
        allContracts.map(c =>
          new CompactJsonScanHttpEncodings(identity, identity)
            .javaToHttpActiveContract(c.eventId, c.event)
        ) should contain theSameElementsInOrderAs allContractsFromS3

        /* We hard-code the expected digests to enforce that the persisted data format does not change.
           These values must not be modified unless there is a conscious decision to change the persisted format,
           with a migration plan for how to apply it consistently across SVs. */
        bucketConnection
          .getChecksums(objectKeys.toSeq)
          .futureValue
          .map(_.checksum) should contain theSameElementsInOrderAs Seq(
          "ViKwAawccbUGu7VOF9K+w1fwXOJL82x8KtlPR8fE5QQ=",
          "0JsYpCrjL3Iesxvba4mq6JazsAq3iIAKWPjViQhQDd0=",
          "uGbENvaUVHMbZ5aVwR0iezkR1tpO0plZPXs79Rg2Kx0=",
          "ecGsxj9T8BgMPgaguPcKJK9tomTEPuSv216vqvGrtVE=",
          "CVECMUsWmg4hN0gdI2mOhXPZabJjxYNft/J0e3Yo/JU=",
          "EGexqBExmi9b6H+kjsD+4aizbR6pBP3qQ6LXmMfiXMY=",
          "ciCi6myO535U0y+eeZS90agy4QwBueeWGAZnnbr2zvM=",
        )
      }
    }

    "correctly process multiple ACS snapshots" in {
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
      val ts1 = CantonTimestamp.tryFromInstant(Instant.now().truncatedTo(ChronoUnit.DAYS))
      val ts2 = ts1.add(3.hours)
      val ts3 = ts1.add(24.hours)
      val store = new MockAcsSnapshotStore(ts1)
      val updateHistory = mockUpdateHistory()
      val s3BucketConnection = getS3BucketConnectionWithInjectedErrors(bucketConnection)
      val metricsFactory = new InMemoryMetricsFactory

      val kvProvider = mkProvider.futureValue

      val retryProvider =
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
      val bulkStorage = new AcsSnapshotBulkStorage(
        bulkStorageTestConfig,
        appConfig,
        store.store,
        updateHistory,
        s3BucketConnection,
        kvProvider,
        new HistoryMetrics(metricsFactory)(MetricsContext.Empty),
        loggerFactory,
      )

      val svc = bulkStorage.asRetryableService(
        AutomationConfig(pollingInterval = NonNegativeFiniteDuration.ofSeconds(1)), // Fast retries
        new WallClock(timeouts, loggerFactory),
        retryProvider,
      )

      def assertLatestSnapshotInMetrics(ts: CantonTimestamp) = {
        val latestSnapshotMetrics = metricsFactory.metrics.gauges
          .get(
            SpliceMetrics.MetricsPrefix :+ "history" :+ "bulk-storage" :+ "latest-acs-snapshot"
          )
          .value
        latestSnapshotMetrics
          .get(MetricsContext.Empty)
          .value
          .value
          .get()
          ._1 shouldBe ts.toEpochMilli * 1000
      }
      def assertGetObjects(
          queryTs: CantonTimestamp,
          expectedTs: CantonTimestamp,
          expectedNumObjects: Int,
      ) = {
        val getObjectsResult = bulkStorage.getAcsSnapshotAtOrBefore(queryTs).futureValue
        getObjectsResult.objects.map(_.key) should contain theSameElementsInOrderAs
          (0 until expectedNumObjects).map(i =>
            s"$expectedTs-Migration-0-${expectedTs.add(1.days)}/ACS_$i.zstd"
          )
        getObjectsResult.objects.map(_.checksum).foreach {
          // We test elsewhere that computed and persisted checksums are correct, so here we just check that they are present and not empty
          _ should not be empty
        }
        succeed
      }

      Using.resources(svc, retryProvider) { (_, _) =>
        val ex = bulkStorage.getAcsSnapshotAtOrBefore(ts1).failed.futureValue
        ex shouldBe a[StatusRuntimeException]
        ex.asInstanceOf[StatusRuntimeException]
          .getStatus
          .getCode shouldBe io.grpc.Status.Code.NOT_FOUND
        ex.getMessage should include("no snapshot in bulk storage yet")

        clue("Initially, a single snapshot is dumped") {
          eventually(4.minutes) {
            val persistedTs1 = kvProvider.getLatestAcsSnapshotInBulkStorage().value.futureValue
            persistedTs1 shouldBe Some(TimestampWithMigrationId(ts1, 0))
          }
          assertLatestSnapshotInMetrics(ts1)
          assertGetObjects(ts1, ts1, 7)
        }

        clue(
          "Add another snapshot to the store, which is not yet dumped because of the longer period on bulk storage"
        ) {
          loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
            store.addSnapshot(ts2),
            logEntries => {
              forExactly(
                1,
                logEntries,
              )(logEntry =>
                logEntry.message should (include(s"Skipping snapshot at timestamp $ts2"))
              )
            },
          )
          assertLatestSnapshotInMetrics(ts1)
          assertGetObjects(ts2, ts1, 7)
        }

        clue("Add one more snapshot to the store, at the end of the period") {
          store.addSnapshot(ts3)

          eventually(4.minutes) {
            val persistedTs3 = kvProvider.getLatestAcsSnapshotInBulkStorage().value.futureValue
            persistedTs3.value shouldBe TimestampWithMigrationId(ts3, 0)
          }
          assertLatestSnapshotInMetrics(ts3)
          assertGetObjects(ts3, ts3, 7)
        }

        val ex1 = bulkStorage
          .getAcsSnapshotAtOrBefore(ts1.minus(java.time.Duration.ofDays(1)))
          .failed
          .futureValue
        ex1 shouldBe a[StatusRuntimeException]
        ex1
          .asInstanceOf[StatusRuntimeException]
          .getStatus
          .getCode shouldBe io.grpc.Status.Code.NOT_FOUND
        ex1.getMessage should include("this may be because the timestamp is before network genesis")

      }
    }
  }

  private def mockUpdateHistory() = {
    val store = mock[UpdateHistory]
    when(store.isReady).thenReturn(true)
    when(
      store.isHistoryBackfilled(anyLong)(any[TraceContext])
    ).thenReturn(Future.successful(true))
    store
  }

  class MockAcsSnapshotStore(val initialSnapshotTimestamp: CantonTimestamp) {
    private var snapshots = Seq(initialSnapshotTimestamp)
    val store = mockAcsSnapshotStore(acsSnapshotSize)

    def addSnapshot(timestamp: CantonTimestamp) = { snapshots = snapshots :+ timestamp }

    def mockAcsSnapshotStore(snapshotSize: Int): AcsSnapshotStore = {
      val store = mock[AcsSnapshotStore]
      val partyId = mkPartyId("alice")
      when(
        store.queryAcsSnapshot(
          anyLong,
          any[CantonTimestamp],
          any[Option[Long]],
          any[Limit],
          any[Seq[PartyId]],
          any[Seq[PackageQualifiedName]],
        )(any[TraceContext])
      ).thenAnswer {
        (
            migration: Long,
            timestamp: CantonTimestamp,
            after: Option[Long],
            limit: Limit,
            _: Seq[PartyId],
            _: Seq[PackageQualifiedName],
        ) =>
          if (snapshots.contains(timestamp)) {
            Future {
              val remaining = snapshotSize - after.getOrElse(0L)
              val numElems = math.min(limit.limit.toLong, remaining)
              val result = QueryAcsSnapshotResult(
                migration,
                timestamp,
                Vector
                  .range(0, numElems)
                  .map(i => {
                    val idx = i + after.getOrElse(0L)
                    val amt = amulet(
                      partyId,
                      BigDecimal(idx),
                      0L,
                      BigDecimal(0.1),
                      contractId = LfContractId.assertFromString("00" + f"$idx%064x").coid,
                    )
                    SpliceCreatedEvent(s"#event_id_$idx:1", toCreatedEvent(amt))
                  }),
                if (numElems < remaining) Some(after.getOrElse(0L) + numElems) else None,
              )
              result
            }
          } else {
            Future.failed(
              new RuntimeException(
                s"Unexpected timestamp $timestamp. Known snapshots are: $snapshots"
              )
            )
          }
      }

      when(
        store.lookupSnapshotAfter(
          anyLong,
          any[CantonTimestamp],
        )(any[TraceContext])
      ).thenAnswer {
        (
            _: Long,
            timestamp: CantonTimestamp,
        ) =>
          Future.successful {
            snapshots
              .filter(_ > timestamp)
              .sorted
              .headOption
              .map(next =>
                AcsSnapshotStore.AcsSnapshot(
                  // only record time and migration ID are used, everything else is ignored
                  snapshotRecordTime = next,
                  migrationId = 0L,
                  0L,
                  0L,
                  0L,
                  None,
                  None,
                )
              )
          }

      }

      store
    }
  }

  def getS3BucketConnectionWithInjectedErrors(
      bucketConnection: S3BucketConnection
  ): S3BucketConnection = {
    val s3BucketConnectionWithErrors = Mockito.spy(bucketConnection)
    var failureCount = 0
    val _ = doAnswer { (invocation: InvocationOnMock) =>
      val args = invocation.getArguments
      args.toList match {
        case (key: String) :: _ if key.endsWith("2.zstd") =>
          if (failureCount < 1) {
            failureCount += 1
            throw new RuntimeException(s"Simulated S3 error (#$failureCount)")
          } else {
            failureCount = 0
            logger.debug(s"No Simulated S3 error, resetting failureCount to 0")
            invocation.callRealMethod().asInstanceOf[s3BucketConnectionWithErrors.AppendWriteObject]
          }
        case _ =>
          invocation.callRealMethod().asInstanceOf[s3BucketConnectionWithErrors.AppendWriteObject]
      }
    }.when(s3BucketConnectionWithErrors)
      /* Throwing an exception on creation of the object is not really realistic, as that is not expected to fail,
       * but it's easier to do here than to catch the actual multi-part writes to it, and is good enough for
       * testing the retries of the whole flow.
       */
      .newAppendWriteObject(anyString)(any[ExecutionContext])
    s3BucketConnectionWithErrors
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
