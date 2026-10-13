// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.daml.metrics.api.testing.InMemoryMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.WallClock
import io.grpc.StatusRuntimeException
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{ScanKeyValueProvider, ScanKeyValueStore}
import org.lfdecentralizedtrust.splice.store.{
  HasS3Mock,
  HistoryMetrics,
  StoreTestBase,
  TimestampWithMigrationId,
}

import scala.concurrent.Future
import scala.util.Using
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.scalatest.Assertion
import org.slf4j.event.Level

import java.time.Instant

class AcsSnapshotBulkStorageCommitFromStagingTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest {

  val bulkStorageTestConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 3,
    bulkAcsSnapshotPeriodHours = 24,
    bulkZstdBlockSize = 1000L,
    bulkZstdFrameSize = 10000L,
    bulkMaxFileSize = 50000L,
    zstdCompressionLevel = 3,
  )
  val appConfig = BulkStorageConfig(
    snapshotPollingInterval = NonNegativeFiniteDuration.ofSeconds(5),
    bftCheckEnabled = false, // bft checks are tested elsewhere
  )

  override val initialBuckets: Seq[String] = Seq("staging", "committed")

  "AcsSnapshotBulkStorageCommitFromStaging" should {
    "successfully move ACS snapshot objects from staging to committed S3 bucket" in {

      val stagingConnection =
        new S3BucketConnectionForUnitTests(s3ConfigMock("staging"), loggerFactory)
      val committedConnection =
        new S3BucketConnectionForUnitTests(s3ConfigMock("committed"), loggerFactory)
      val metricsFactory = new InMemoryMetricsFactory
//      val historyMetrics = new HistoryMetrics(metricsFactory)(MetricsContext.Empty)
      val kvProvider = mkKvProvider.futureValue
      def ts(day: Int): CantonTimestamp = CantonTimestamp.tryFromInstant(
        Instant.parse(s"2026-01-01T00:00:00Z").plus(day.toLong, java.time.temporal.ChronoUnit.DAYS)
      )

      val acsStagingProgress = new AcsSnapshotBulkStoragePersistentProgress(
        BulkStorage.acsStagingKvStoreKey,
        BulkStorage.firstAcsSnapshotTimestampKvStoreKey,
        kvProvider,
        HistoryMetrics(metricsFactory, 0L).BulkStorage.latestAcsSnapshotStaging,
        loggerFactory,
      )
      val acsCommittedProgress = new AcsSnapshotBulkStoragePersistentProgress(
        BulkStorage.acsCommittedKvStoreKey,
        BulkStorage.firstAcsSnapshotTimestampKvStoreKey,
        kvProvider,
        HistoryMetrics(metricsFactory, 0L).BulkStorage.latestAcsSnapshotCommitted,
        loggerFactory,
      )
      val reader = new BulkStorageReader(
        acsStagingProgress,
        acsCommittedProgress,
        null, // no ACS snapshots in this test
        null, // no ACS snapshots in this test
        bulkStorageTestConfig,
        stagingConnection,
        committedConnection,
        () => Future.successful(None),
        loggerFactory,
      )

      def withNewCommitService(body: => Assertion): Assertion = {
        val retryProvider =
          RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
        val acsCommittedWriter = new AcsSnapshotBulkStorageCommitFromStaging(
          stagingConnection,
          committedConnection,
          reader,
          appConfig,
          null, // not used when bft reads are disabled
          _ => (),
          loggerFactory,
        )
        val commitService = {
          new AcsSnapshotBulkStorage(
            "AcsSnapshotBulkStorageCommitted",
            acsCommittedWriter,
            acsCommittedProgress,
            appConfig,
            Source.single(true).mapMaterializedValue(_ => Cancellable.alreadyCancelled),
            loggerFactory,
          ).asPekkoRetryingService(
            AutomationConfig(pollingInterval =
              NonNegativeFiniteDuration.ofSeconds(1)
            ), // Fast retries
            new WallClock(timeouts, loggerFactory),
            retryProvider,
          )
        }
        Using.resources(commitService, retryProvider) { (_, _) => body }
      }

      def createDummySnapshot(day: Int, objectCount: Int): Unit = {
        createDummySnapshotWithoutMarkingProcessed(day, objectCount)
        acsStagingProgress
          .persistLatestProcessedSnapshotTimestamp(TimestampWithMigrationId(ts(day), 0))
          .futureValue
      }

      def createDummySnapshotWithoutMarkingProcessed(
          day: Int,
          objectCount: Int,
      ): Unit = {
        (0 until objectCount).foreach { i =>
          ScanStorageConfig.Encoding.all.toList.foreach { encoding =>
            stagingConnection
              .createObject(
                s"${bulkStorageTestConfig.getSegmentFolder(ts(day), None)}/${encoding.storageKey("ACS", i)}",
                s"dummy acs snapshot at ${ts(day)} (object $i)".getBytes,
              )
              .futureValue
          }
        }
      }

      def assertCommittedObjectsForSnapshot(day: Int, expectedCount: Int): Assertion = {
        val expectedKeys = (0 until expectedCount).flatMap { i =>
          ScanStorageConfig.Encoding.all.toList.map { encoding =>
            s"${bulkStorageTestConfig.getSegmentFolder(ts(day), None)}/${encoding.storageKey("ACS", i)}"
          }
        }
        reader
          .getCommittedObjectsForAcsSnapshotAtOrBefore(ts(day), ScanStorageConfig.Encoding.all)
          .futureValue
          .objects
          .map(_.key) should contain theSameElementsAs
          expectedKeys
      }
      def assertNoStagingObjectsForSnapshot(day: Int): Assertion = {
        reader
          .getStagingObjectsForAcsSnapshotAt(ts(day))
          .failed
          .futureValue
          .asInstanceOf[StatusRuntimeException]
          .getStatus
          .getCode shouldBe io.grpc.Status.Code.NOT_FOUND
      }

      withNewCommitService {
        // Create full dummy ACS snapshots for first two timestamps
        createDummySnapshot(day = 1, objectCount = 1)
        createDummySnapshot(day = 2, objectCount = 2)
        // Create a third snapshot with two objects, but do not mark it as processed yet
        // (simulating that the writer from DB is still not done with it)
        createDummySnapshotWithoutMarkingProcessed(day = 3, objectCount = 2)

        eventually() {
          acsCommittedProgress.readLatestProcessedSnapshotTimestamp.futureValue.map(
            _.timestamp
          ) shouldBe Some(ts(2))
        }
        assertCommittedObjectsForSnapshot(day = 1, expectedCount = 1)
        assertCommittedObjectsForSnapshot(day = 2, expectedCount = 2)
        assertNoStagingObjectsForSnapshot(1)
        assertNoStagingObjectsForSnapshot(2)
        reader.getStagingObjectsForAcsSnapshotAt(ts(3)).futureValue.objects should not be empty

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.DEBUG))(
          {},
          logEntries => {
            forAtLeast(1, logEntries)(entry =>
              entry.message should include(
                s"Latest snapshot in staging bulk storage is at ${ts(2)}, which is not after the requested timestamp ${ts(2)}"
              )
            )
          },
        )

        acsStagingProgress
          .persistLatestProcessedSnapshotTimestamp(TimestampWithMigrationId(ts(3), 0))
          .futureValue
        eventually() {
          acsCommittedProgress.readLatestProcessedSnapshotTimestamp.futureValue.map(
            _.timestamp
          ) shouldBe Some(ts(3))
        }
        assertCommittedObjectsForSnapshot(day = 3, expectedCount = 2)
        assertNoStagingObjectsForSnapshot(3)
      }

      // Create a snapshot, and copy one object before starting the service,
      // to simulate a restart after copying has started
      createDummySnapshot(day = 4, objectCount = 3)
      committedConnection
        .copyObject(
          "staging",
          s"${bulkStorageTestConfig.getSegmentFolder(ts(4), None)}/${ScanStorageConfig.Encoding.CompactJson
              .storageKey("ACS", 0)}",
        )
        .futureValue

      withNewCommitService {
        eventually() {
          acsCommittedProgress.readLatestProcessedSnapshotTimestamp.futureValue.map(
            _.timestamp
          ) shouldBe Some(ts(4))
        }
        assertCommittedObjectsForSnapshot(day = 4, expectedCount = 3)
        assertNoStagingObjectsForSnapshot(4)
      }

      // Create a snapshot, and move it all to committed, but do not mark it as processed yet in the committed progress,
      // to simulate a restart after moving has completed but before marking as processed
      createDummySnapshot(day = 5, objectCount = 2)
      Seq(0, 1).foreach { i =>
        committedConnection
          .copyObject(
            "staging",
            s"${bulkStorageTestConfig
                .getSegmentFolder(ts(5), None)}/${ScanStorageConfig.Encoding.CompactJson.storageKey("ACS", i)}",
          )
          .futureValue
        stagingConnection
          .deleteObject(
            s"${bulkStorageTestConfig
                .getSegmentFolder(ts(5), None)}/${ScanStorageConfig.Encoding.CompactJson.storageKey("ACS", i)}"
          )
          .futureValue
      }

      withNewCommitService {
        eventually() {
          acsCommittedProgress.readLatestProcessedSnapshotTimestamp.futureValue.map(
            _.timestamp
          ) shouldBe Some(ts(5))
        }
        assertCommittedObjectsForSnapshot(day = 5, expectedCount = 2)
        assertNoStagingObjectsForSnapshot(5)
      }

    }
  }

  def mkKvProvider: Future[ScanKeyValueProvider] = {
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
