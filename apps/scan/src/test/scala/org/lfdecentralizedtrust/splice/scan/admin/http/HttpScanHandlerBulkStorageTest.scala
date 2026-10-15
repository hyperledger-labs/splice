// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  PackageVersionSupport,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource
import org.lfdecentralizedtrust.splice.scan.ScanSynchronizerNode
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.scan.metrics.ScanHttpApiMetrics
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  AppActivityStore,
  ScanEventStore,
  ScanStore,
}
import org.lfdecentralizedtrust.splice.scan.store.bulk.{
  AcsSnapshotBulkStoragePersistentProgress,
  BulkStorageReader,
  UpdateHistoryBulkStoragePersistentProgress,
  UpdatesSegment,
}
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore
import org.lfdecentralizedtrust.splice.store.{
  AppStoreWithIngestion,
  S3BucketConnection,
  TimestampWithMigrationId,
  UpdateHistory,
}
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, ZoneOffset}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class HttpScanHandlerBulkStorageTest extends AnyWordSpec with BaseTest {

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val actorSystem: ActorSystem = ActorSystem("HttpScanHandlerBulkStorageTest")
  implicit val tc: TraceContext = TraceContext.empty

  private val instanceNames = SpliceInstanceNamesConfig(
    networkName = "network",
    networkFaviconUrl = "https://example.invalid/favicon",
    amuletName = "amulet",
    amuletNameAcronym = "A",
    nameServiceName = "name-service",
    nameServiceNameAcronym = "NS",
  )

  private def handler(
      bulkStorage: Option[BulkStorageReader],
      publicUrlO: Option[Uri] = None,
  ): HttpScanHandler = {
    val scanStore = mock[ScanStore]
    val storeWithIngestion = mock[AppStoreWithIngestion[ScanStore]]
    when(storeWithIngestion.store).thenReturn(scanStore)

    new HttpScanHandler(
      svParty = PartyId.tryFromProtoPrimitive("sv::dummy"),
      svUserName = "sv-user",
      spliceInstanceNames = instanceNames,
      participantAdminConnection = mock[ParticipantAdminConnection],
      synchronizerNodeService = mock[SynchronizerNodeService[ScanSynchronizerNode]],
      storeWithIngestion = storeWithIngestion,
      updateHistory = mock[UpdateHistory],
      appRewardsStore = mock[DbScanAppRewardsStore],
      appActivityStore = mock[AppActivityStore],
      snapshotStore = mock[AcsSnapshotStore],
      eventStore = mock[ScanEventStore],
      bulkStorage = bulkStorage,
      scanApiMetrics = mock[ScanHttpApiMetrics],
      dsoAnsResolver = mock[DsoAnsResolver],
      miningRoundsCacheTimeToLiveOverride = None,
      enableForcedAcsSnapshots = false,
      clock = mock[Clock],
      loggerFactory = NamedLoggerFactory.root,
      packageVersionSupport = mock[PackageVersionSupport],
      bftSequencers = Seq.empty,
      initialRound = "0",
      externalTransactionHashThresholdTime = None,
      updateHistoryMaxPageSize = 100,
      publicUrlO = publicUrlO,
      lsuRollForwardConfigO = None,
      perAcsSnapshotTablesEnabled = true,
    )
  }

  private def assertGrpcError[A](
      f: => Future[A],
      expectedCode: Status.Code,
      expectedDescription: String,
  ): Unit = {
    val ex = f.failed.futureValue
    ex shouldBe a[StatusRuntimeException]
    val grpcEx = ex.asInstanceOf[StatusRuntimeException]
    grpcEx.getStatus.getCode shouldBe expectedCode
    grpcEx.getStatus.getDescription should include(expectedDescription)
  }

  private def snapshotProgressAt(instant: String): TimestampWithMigrationId =
    TimestampWithMigrationId(
      com.digitalasset.canton.data.CantonTimestamp.assertFromInstant(Instant.parse(instant)),
      0L,
    )

  private def updateProgress(fromInstant: String, toInstant: String): UpdatesSegment =
    UpdatesSegment(
      fromTimestamp = snapshotProgressAt(fromInstant),
      toTimestamp = snapshotProgressAt(toInstant),
    )

  private def snapshotsProgress(
      progressO: Option[TimestampWithMigrationId]
  ): AcsSnapshotBulkStoragePersistentProgress = {
    val progress = mock[AcsSnapshotBulkStoragePersistentProgress]
    when(
      progress.readLatestProcessedSnapshotTimestamp(any[TraceContext], any[ExecutionContext])
    ).thenReturn(Future.successful(progressO))
    progress
  }

  private def updatesProgress(
      progressO: Option[UpdatesSegment]
  ): UpdateHistoryBulkStoragePersistentProgress = {
    val progress = mock[UpdateHistoryBulkStoragePersistentProgress]
    when(progress.readLatestProcessedSegment(any[TraceContext], any[ExecutionContext]))
      .thenReturn(Future.successful(progressO))
    progress
  }

  private def bulkStorageReader(
      snapshotProgressO: Option[TimestampWithMigrationId],
      updateProgressO: Option[UpdatesSegment],
      committedSnapshotProgressO: Option[TimestampWithMigrationId] = None,
      committedUpdateProgressO: Option[UpdatesSegment] = None,
      firstOwnSegmentStart: Option[CantonTimestamp] = None,
  ): BulkStorageReader =
    new BulkStorageReader(
      acsSnapshotStagingProgress = snapshotsProgress(snapshotProgressO),
      acsSnapshotCommittedProgress = snapshotsProgress(committedSnapshotProgressO),
      updateHistoryStagingProgress = updatesProgress(updateProgressO),
      updateHistoryCommittedProgress = updatesProgress(committedUpdateProgressO),
      storageConfig = mock[ScanStorageConfig],
      stagingS3Connection = mock[S3BucketConnection],
      committedS3Connection = mock[S3BucketConnection],
      firstOwnSegmentStart = () => Future.successful(firstOwnSegmentStart),
      loggerFactory = NamedLoggerFactory.root,
    )

  private def progressResponse(
      bulkStorage: BulkStorageReader,
      recordTime: String,
      bucket: definitions.BulkStorageBucket = definitions.BulkStorageBucket.Staging,
  ): definitions.GetBulkObjectsProgressResponse =
    inside(
      handler(bulkStorage = Some(bulkStorage))
        .getBulkObjectsProgress(ScanResource.GetBulkObjectsProgressResponse)(
          Instant.parse(recordTime).atOffset(ZoneOffset.UTC),
          bucket,
        )(TraceContext.empty)
        .futureValue
    ) { case ScanResource.GetBulkObjectsProgressResponseOK(value) => value }

  "HttpScanHandler bulk-storage endpoints" should {
    "return UNIMPLEMENTED when bulk storage is not configured for checksum lookups" in {
      val h = handler(bulkStorage = None)
      val request = definitions.GetBulkObjectChecksumsRequest(
        objectKeys = Vector("object-1")
      )
      assertGrpcError(
        h.getBulkObjectChecksums(ScanResource.GetBulkObjectChecksumsResponse)(request)(
          TraceContext.empty
        ),
        Status.Code.UNIMPLEMENTED,
        "Bulk storage is not configured",
      )
    }

    "GetBulkObjectsProgress answers available when enough progress was made" in {
      val snapshotProgress = snapshotProgressAt("2023-12-31T00:00:00Z")
      val updateRange = updateProgress("2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z")
      val response = progressResponse(
        bulkStorageReader(Some(snapshotProgress), Some(updateRange)),
        "2023-12-31T00:00:00Z",
      )
      response.availability shouldBe definitions.BulkObjectsAvailability.Available
    }

    "GetBulkObjectsProgress answers processing when snapshot progress is behind the requested record time" in {
      val snapshotProgress = snapshotProgressAt("2023-12-31T00:00:00Z")
      val updateRange = updateProgress("2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z")
      val response = progressResponse(
        bulkStorageReader(Some(snapshotProgress), Some(updateRange)),
        "2024-01-01T00:00:00Z",
      )
      response.availability shouldBe definitions.BulkObjectsAvailability.Processing
    }

    "GetBulkObjectsProgress answers processing when updates progress is behind the requested record time" in {
      val snapshotProgress = snapshotProgressAt("2024-01-02T00:00:00Z")
      val updateRange = updateProgress("2023-12-30T00:00:00Z", "2023-12-31T00:00:00Z")
      val response = progressResponse(
        bulkStorageReader(Some(snapshotProgress), Some(updateRange)),
        "2024-01-01T00:00:00Z",
      )
      response.availability shouldBe definitions.BulkObjectsAvailability.Processing
    }

    "GetBulkObjectsProgress answers processing when progress is not initialized" in {
      val response = progressResponse(bulkStorageReader(None, None), "2024-01-01T00:00:00Z")
      response.availability shouldBe definitions.BulkObjectsAvailability.Processing
    }

    "GetBulkObjectsProgress answers backfilling before the first own segment when the data is not held" in {
      val response = progressResponse(
        bulkStorageReader(
          None,
          None,
          firstOwnSegmentStart =
            Some(CantonTimestamp.assertFromInstant(Instant.parse("2024-01-02T00:00:00Z"))),
        ),
        "2024-01-01T00:00:00Z",
      )
      response.availability shouldBe definitions.BulkObjectsAvailability.Backfilling
    }

    "GetBulkObjectsProgress answers available before the first own segment once the data is held" in {
      val response = progressResponse(
        bulkStorageReader(
          Some(snapshotProgressAt("2024-01-01T00:00:00Z")),
          Some(updateProgress("2023-12-31T00:00:00Z", "2024-01-01T00:00:00Z")),
          firstOwnSegmentStart =
            Some(CantonTimestamp.assertFromInstant(Instant.parse("2024-01-02T00:00:00Z"))),
        ),
        "2024-01-01T00:00:00Z",
      )
      response.availability shouldBe definitions.BulkObjectsAvailability.Available
    }

    "GetBulkObjectsProgress answers from the committed progress for the committed bucket" in {
      val reader = bulkStorageReader(
        Some(snapshotProgressAt("2024-01-02T00:00:00Z")),
        Some(updateProgress("2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z")),
        committedSnapshotProgressO = Some(snapshotProgressAt("2023-12-31T00:00:00Z")),
        committedUpdateProgressO =
          Some(updateProgress("2023-12-30T00:00:00Z", "2023-12-31T00:00:00Z")),
      )
      progressResponse(reader, "2024-01-01T00:00:00Z").availability shouldBe
        definitions.BulkObjectsAvailability.Available
      progressResponse(
        reader,
        "2024-01-01T00:00:00Z",
        definitions.BulkStorageBucket.Committed,
      ).availability shouldBe definitions.BulkObjectsAvailability.Processing
    }
  }
}
