// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import cats.data.NonEmptyList
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
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider, SpliceMetrics}
import org.lfdecentralizedtrust.splice.http.v0.definitions as httpApi
import org.lfdecentralizedtrust.splice.scan.admin.http.{
  CompactJsonScanHttpEncodings,
  ProtobufJsonScanHttpEncodings,
  ScanHttpEncodings,
}
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
}
import org.lfdecentralizedtrust.splice.util.PackageQualifiedName
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.slf4j.event.Level

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import scala.util.Using

class AcsSnapshotBulkStorageWriterFromDbTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest {

  val acsSnapshotSize = 48500
  val bulkStorageTestConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 3,
    bulkAcsSnapshotPeriodHours = 24,
    bulkZstdBlockSize = 4000L,
    bulkZstdFrameSize = 20000L,
    bulkMaxFileSize = 50000L,
    zstdCompressionLevel = 3,
  )
  val appConfig = BulkStorageConfig(
    snapshotPollingInterval = NonNegativeFiniteDuration.ofSeconds(5)
  )

  "AcsSnapshotBulkStorage" should {
    "successfully dump a single ACS snapshot" in {
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock(), loggerFactory)
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
        def checkEncoding(encoding: ScanStorageConfig.Encoding) = {
          /* We hard-code the expected digests to enforce that the persisted data format does not change.
             These values must not be modified unless there is a conscious decision to change the persisted format,
             with a migration plan for how to apply it consistently across SVs. */
          val (encodings, expectedDigests): (ScanHttpEncodings, Seq[String]) =
            encoding match {
              case ScanStorageConfig.Encoding.CompactJson =>
                (
                  new CompactJsonScanHttpEncodings(identity, identity),
                  Seq(
                    "0WSNhAAj6bT8k+U8zRyKxZWf1UGi39JcZwCNTsEhwyw=",
                    "pU4VHpq4B7mEkVeqq8dYtkx7c2q59oHFmUZKy2ZymgM=",
                    "Je6fyr9Q0VbAcPupMdJ4i9l7N1VfRqMdaIVIRjP1IEk=",
                    "2KkRlMIHhccycmKMdioobNXDf9rgFJKLru0OUgpkdWw=",
                    "rrMB8Zjl+QMoa1MifgIcvJbmeo+Wpy73Qes8WgWKndk=",
                    "O3D8NcYS6UxbfH9ahLonKLjS8XxMShAjvue7HvK4BUk=",
                    "ml4eJRZDu8/L+SocfGTR78fr9YrNmnlMZmCju05WwDI=",
                    "5BsR5nZj9EiabZkKH+X56gSlfFeE3YEZMZN1ImXXeNU=",
                    "NVjKR3wSk4pcjymL65acvvp9Rhhvx+W7Qsp8Ct/YvuA=",
                    "ZPiWc4C64H19fO1Wk06zuyAFu6T7mC9nWcinRarDx1w=",
                    "r2kvpv122uaKVAfCoSxr0vvvQRmRwsNcJZM78dqH2Oo=",
                    "9t2rduH6EfB9JkGmDMP9MC7mPu82IP4aH3Dfb8mJgNY=",
                  ),
                )
              case ScanStorageConfig.Encoding.ProtobufJson =>
                (
                  ProtobufJsonScanHttpEncodings,
                  Seq(
                    "Q95yH0Uu+C5LZe8FRF6cyPafjrjPpOd9G+MK671c41I=",
                    "ALq5hDfPJGw0L9DGGC9TxLWNrinCkwZIK82Ij/j4XnY=",
                    "Oqr/CoMwXQBpv/EN7LMpNYj5mNafl7g2X/tNPLFQBKA=",
                    "Z27oeKANd3jRKI3KhsXjKH3K0+D2yLldMplDsMs5syg=",
                    "0k6FOdvZ5YwhTdSqo+MhOjzUgh+g310tejKT9ldXyWk=",
                    "WTA4JOkq0l4CZZ7IvAJmTgLNo1MZv8gNgaeBZJ83b40=",
                    "hA2DDFmYO0NiE9ttOEQMwyDP+KDIAS/tN9ygbv6MVkM=",
                    "AsArKIbUFn/NTLGichdhn+NJT0VsHKOcyxuUH2Z4JJ8=",
                    "5CICA0sm6z4P66XnGl3ysRB7MGLu4i8nllfrK2liTV4=",
                    "HLTy9EtFCSxEnGw43IqsnqKijFlpNGw2nTBNFwQK6/Q=",
                    "MVselLUvB3xCmC8ZOMcs2fB1a39VvkFTX3EtXvxg43g=",
                    "VWhtLyuY5SZXpc+G7mYyyOM+iJ/8B1v2r5pWH4C23Ps=",
                    "nMOuy1YOx7nw6uuT+0CtpEKkdz71ZF7i4Erqlz1OyqI=",
                  ),
                )
            }
          val objectKeys = s3Objects.contents.asScala
            .map(_.key())
            .filter(
              encoding.storageKeyRegex("ACS").matches
            )
            .sortBy { key =>
              """_(\d+)\.zstd$""".r
                .findFirstMatchIn(key)
                .map(_.group(1).toInt)
                .getOrElse(fail(s"Unexpected object key format: $key"))
            }
          objectKeys should have length expectedDigests.length.toLong
          objectKeys.foreach(
            _ should startWith(s"2026-01-02T00:00:00Z~2026-01-03T00:00:00Z/ACS_${encoding.key}")
          )
          val objectCountMetrics = metricsFactory.metrics.counters.get(
            SpliceMetrics.MetricsPrefix :+ "history" :+ "bulk-storage" :+ "object-count"
          )
          val numObjectsFromMetric = objectCountMetrics.value
            .get(MetricsContext.Empty)
            .value
            .markers
            .get(
              MetricsContext(
                "object_type" -> "ACS_snapshots",
                "encoding" -> encoding.key,
                "bucket" -> "staging",
              )
            )
            .value
            .get()
          numObjectsFromMetric shouldBe expectedDigests.length

          val allContractsFromS3 = objectKeys.flatMap(
            readUncompressAndDecode(
              bucketConnection,
              io.circe.parser.decode[httpApi.ActiveContract],
            )
          )
          allContracts.length shouldBe allContractsFromS3.length
          allContracts.zip(allContractsFromS3).foreach { case (c1, c2) =>
            encodings.javaToHttpActiveContract(c1.eventId, c1.recordTime, c1.event) shouldBe c2
          }
          allContracts.map(c =>
            encodings.javaToHttpActiveContract(c.eventId, c.recordTime, c.event)
          ) should contain theSameElementsInOrderAs allContractsFromS3

          bucketConnection
            .getChecksums(objectKeys.toSeq)
            .futureValue
            .map(_.checksum) should contain theSameElementsInOrderAs expectedDigests
        }

        checkEncoding(ScanStorageConfig.Encoding.CompactJson)
        checkEncoding(ScanStorageConfig.Encoding.ProtobufJson)
      }
    }

    "correctly process multiple ACS snapshots" in {
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock(), loggerFactory)
      val ts1 = CantonTimestamp.tryFromInstant(Instant.now().truncatedTo(ChronoUnit.DAYS))
      val ts2 = ts1.add(3.hours)
      val ts3 = ts1.add(24.hours)
      val store = new MockAcsSnapshotStore(ts1)
      val s3BucketConnection = getS3BucketConnectionWithInjectedErrors(bucketConnection)
      val metricsFactory = new InMemoryMetricsFactory

      val kvProvider = mkProvider.futureValue

      val retryProvider = {
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
      }
      val historyMetrics = new HistoryMetrics(metricsFactory)(MetricsContext.Empty)
      val acsSnapshotWriter = new AcsSnapshotBulkStorageWriterFromDb(
        bulkStorageTestConfig,
        appConfig,
        store.store,
        s3BucketConnection,
        historyMetrics,
        loggerFactory,
      )
      val progress = new AcsSnapshotBulkStoragePersistentProgress(
        "latest_acs_snapshot_in_bulk_storage",
        "first_acs_snapshot_in_bulk_storage",
        kvProvider,
        historyMetrics.BulkStorage.latestAcsSnapshotStaging,
        loggerFactory,
      )
      val bulkStorage = new AcsSnapshotBulkStorage(
        "AcsSnapshotBulkStorageUnitTest",
        acsSnapshotWriter,
        progress,
        appConfig,
        Source.single(true).mapMaterializedValue(_ => Cancellable.alreadyCancelled),
        loggerFactory,
      )
      val reader = new BulkStorageReader(
        acsSnapshotStagingProgress = progress,
        acsSnapshotCommittedProgress = progress,
        updateHistoryStagingProgress = null, // no updates history in this test
        updateHistoryCommittedProgress = null, // no updates history in this test
        bulkStorageTestConfig,
        s3BucketConnection,
        s3BucketConnection, // we use the same bucket for staging and committed for this test, as we don't run the commit from staging flow
        firstOwnSegmentStart = () => Future.successful(None),
        loggerFactory = loggerFactory,
      )

      def assertLatestSnapshotInMetrics(ts: CantonTimestamp) = {
        val latestSnapshotMetrics = metricsFactory.metrics.gauges
          .get(
            SpliceMetrics.MetricsPrefix :+ "history" :+ "bulk-storage" :+ s"latest-acs-snapshot-staging"
          )
          .value
        latestSnapshotMetrics
          .get(MetricsContext.Empty)
          .value
          .value
          .get()
          ._1 shouldBe ts.toEpochMilli * 1000 withClue s"Latest snapshot timestamp in staging bucket should be $ts"
      }
      def assertGetObjects(
          queryTs: CantonTimestamp,
          expectedTs: CantonTimestamp,
          encoding: ScanStorageConfig.Encoding,
          expectedNumObjects: Int,
      ) = {
        val getObjectsResult = reader
          .getCommittedObjectsForAcsSnapshotAtOrBefore(
            queryTs,
            NonEmptyList.one(encoding),
          )
          .futureValue
        val objectKeys = getObjectsResult.objects.map(_.key).sortBy { key =>
          """_(\d+)\.zstd$""".r
            .findFirstMatchIn(key)
            .map(_.group(1).toInt)
            .getOrElse(fail(s"Unexpected object key format: $key"))
        }
        objectKeys should contain theSameElementsInOrderAs
          (0 until expectedNumObjects).map(i =>
            s"$expectedTs~${expectedTs.add(1.days)}/${encoding.storageKey("ACS", i)}"
          )
        getObjectsResult.objects.map(_.checksum).foreach {
          // We test elsewhere that computed and persisted checksums are correct, so here we just check that they are present and not empty
          _ should not be empty
        }
        succeed
      }

      val ex = reader
        .getCommittedObjectsForAcsSnapshotAtOrBefore(
          ts1,
          ScanStorageConfig.Encoding.all,
        )
        .failed
        .futureValue
      ex shouldBe a[StatusRuntimeException]
      ex.asInstanceOf[StatusRuntimeException]
        .getStatus
        .getCode shouldBe io.grpc.Status.Code.NOT_FOUND
      ex.getMessage should include("no snapshot in committed bulk storage yet")

      val svc = bulkStorage.asPekkoRetryingService(
        AutomationConfig(pollingInterval = NonNegativeFiniteDuration.ofSeconds(1)), // Fast retries
        new WallClock(timeouts, loggerFactory),
        retryProvider,
      )

      Using.resources(svc, retryProvider) { (_, _) =>
        clue("Initially, a single snapshot is dumped") {
          eventually(4.minutes) {
            val persistedTs1 = progress.readLatestProcessedSnapshotTimestamp.futureValue
            persistedTs1 shouldBe Some(TimestampWithMigrationId(ts1, 0))
          }
          assertLatestSnapshotInMetrics(ts1)
          assertGetObjects(ts1, ts1, ScanStorageConfig.Encoding.CompactJson, 12)
          assertGetObjects(ts1, ts1, ScanStorageConfig.Encoding.ProtobufJson, 13)
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
          assertGetObjects(ts2, ts1, ScanStorageConfig.Encoding.CompactJson, 12)
          assertGetObjects(ts2, ts1, ScanStorageConfig.Encoding.ProtobufJson, 13)
        }

        clue("Add one more snapshot to the store, at the end of the period") {
          store.addSnapshot(ts3)

          eventually(4.minutes) {
            val persistedTs3 = progress.readLatestProcessedSnapshotTimestamp.futureValue
            persistedTs3.value shouldBe TimestampWithMigrationId(ts3, 0)
          }
          assertLatestSnapshotInMetrics(ts3)
          assertGetObjects(ts3, ts3, ScanStorageConfig.Encoding.CompactJson, 12)
          assertGetObjects(ts3, ts3, ScanStorageConfig.Encoding.ProtobufJson, 13)
        }

        val ex1 = reader
          .getCommittedObjectsForAcsSnapshotAtOrBefore(
            ts1.minus(java.time.Duration.ofDays(1)),
            ScanStorageConfig.Encoding.all,
          )
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
          any[Option[AcsSnapshotStore.QueryAcsSnapshotPaginationToken]],
          any[Limit],
          any[Seq[PartyId]],
          any[Seq[PackageQualifiedName]],
        )(any[TraceContext])
      ).thenAnswer {
        (
            migration: Long,
            timestamp: CantonTimestamp,
            after: Option[AcsSnapshotStore.QueryAcsSnapshotPaginationToken],
            limit: Limit,
            _: Seq[PartyId],
            _: Seq[PackageQualifiedName],
        ) =>
          if (snapshots.contains(timestamp)) {
            Future {
              val afterAsLong = after match {
                case Some(
                      AcsSnapshotStore.QueryAcsSnapshotPaginationToken
                        .RowIdQueryAcsSnapshotPaginationToken(value)
                    ) =>
                  value
                case _ => 0L
              }
              val remaining = snapshotSize - afterAsLong
              val numElems = math.min(limit.limit.toLong, remaining)
              val result = QueryAcsSnapshotResult(
                migration,
                timestamp,
                Vector
                  .range(0, numElems)
                  .map(i => {
                    val idx = i + afterAsLong
                    val amt = amulet(
                      partyId,
                      BigDecimal(idx),
                      0L,
                      BigDecimal(0.1),
                      contractId = LfContractId.assertFromString("00" + f"$idx%064x").coid,
                      version = DarResources.amulet_0_1_17, // ensure packageid determinism
                    )
                    SpliceCreatedEvent(
                      s"#event_id_$idx:1",
                      CantonTimestamp.assertFromInstant(amt.createdAt),
                      toCreatedEvent(amt),
                    )
                  }),
                if (numElems < remaining)
                  Some(
                    AcsSnapshotStore.QueryAcsSnapshotPaginationToken
                      .RowIdQueryAcsSnapshotPaginationToken(afterAsLong + numElems)
                  )
                else None,
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
                AcsSnapshotStore.LegacyAcsSnapshot(
                  // only record time and migration ID are used, everything else is ignored
                  snapshotRecordTime = next,
                  migrationId = 0L,
                  0L,
                  0L,
                  0L,
                  None,
                  None,
                  indexesCreated = true,
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
    val failedKeys = ConcurrentHashMap.newKeySet[String]()
    val _ = doAnswer { (invocation: InvocationOnMock) =>
      val args = invocation.getArguments
      args.toList match {
        case (key: String) :: _ if key.endsWith("2.zstd") && failedKeys.add(key) =>
          throw new RuntimeException(s"Simulated S3 error for $key")
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
