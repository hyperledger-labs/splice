// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.lifecycle.{
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  LifeCycle,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, S3Config, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, SpliceLedgerClient}
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  ScanKeyValueProvider,
  ScanStore,
}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, S3BucketConnection, UpdateHistory}

import scala.concurrent.{ExecutionContextExecutor, Future}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import cats.implicits.*
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.PekkoRetryableService
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorage.{
  acsCommittedKvStoreKey,
  acsStagingKvStoreKey,
  firstAcsSnapshotTimestampKvStoreKey,
  updatesCommittedKvStoreKey,
  updatesStagingKvStoreKey,
}
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling.{
  BftBulkObjectListing,
  BulkStorageBackfilling,
  KvBackfillingProgress,
  ScanPeerObjectSource,
  VerifiedObjectCopier,
}
import org.lfdecentralizedtrust.splice.scan.store.historystart.ScanHistoryStart
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.duration.*

class BulkStorage(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    stagingS3Config: S3Config,
    committedS3Config: S3Config,
    acsSnapshotStore: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    currentMigrationId: Long,
    kvProvider: ScanKeyValueProvider,
    historyStart: ScanHistoryStart,
    metricsFactory: LabeledMetricsFactory,
    automationConfig: AutomationConfig,
    backoffClock: Clock,
    store: ScanStore,
    svName: String,
    ledgerClient: SpliceLedgerClient,
    upgradesConfig: UpgradesConfig,
    override val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    actorSystem: ActorSystem,
    tc: TraceContext,
    ec: ExecutionContextExecutor,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends NamedLogging
    with FlagCloseableAsync
    with RetryProvider.Has {

  val stagingConnection = S3BucketConnection(stagingS3Config, loggerFactory)
  val committedConnection = S3BucketConnection(committedS3Config, loggerFactory)
  val historyMetrics = HistoryMetrics(metricsFactory, currentMigrationId)
  val scanConnection = new PeerBftScanConnection(
    store,
    svName,
    ledgerClient,
    automationConfig,
    upgradesConfig,
    backoffClock,
    retryProvider,
    loggerFactory,
  )

  val backfillingCompleteGate: Source[Boolean, Cancellable] =
    Source
      .tick(0.seconds, appConfig.updatesPollingInterval.underlying, ())
      .mapAsync(1)(_ =>
        if (updateHistory.isReady)
          for {
            historyBackfilled <- updateHistory.isHistoryBackfilled(currentMigrationId)
            peerCopyComplete <-
              if (appConfig.backfilling.enabled) backfillingProgress.isComplete
              else Future.successful(true)
          } yield historyBackfilled && peerCopyComplete
        else Future.successful(false)
      )
      .filter(identity)
      .take(1)

  val acsStagingProgress = new AcsSnapshotBulkStoragePersistentProgress(
    acsStagingKvStoreKey,
    firstAcsSnapshotTimestampKvStoreKey,
    kvProvider,
    historyMetrics.BulkStorage.latestAcsSnapshotStaging,
    loggerFactory,
  )
  val acsCommittedProgress = new AcsSnapshotBulkStoragePersistentProgress(
    acsCommittedKvStoreKey,
    firstAcsSnapshotTimestampKvStoreKey,
    kvProvider,
    historyMetrics.BulkStorage.latestAcsSnapshotCommitted,
    loggerFactory,
  )
  private val updatesStagingProgress = new UpdateHistoryBulkStoragePersistentProgress(
    updatesStagingKvStoreKey,
    kvProvider,
    historyMetrics.BulkStorage.latestUpdatesSegmentStaging,
    loggerFactory,
  )
  private val updatesCommittedProgress = new UpdateHistoryBulkStoragePersistentProgress(
    updatesCommittedKvStoreKey,
    kvProvider,
    historyMetrics.BulkStorage.latestUpdatesSegmentCommitted,
    loggerFactory,
  )

  lazy val backfillingProgress = new KvBackfillingProgress(
    updatesStagingProgress,
    acsStagingProgress,
    kvProvider,
    loggerFactory,
  )
  val backfilling: Option[BulkStorageBackfilling] =
    Option.when(appConfig.backfilling.enabled) {
      new BulkStorageBackfilling(
        appConfig.backfilling,
        storageConfig,
        currentMigrationId,
        new BftBulkObjectListing(scanConnection, loggerFactory),
        new VerifiedObjectCopier(
          new ScanPeerObjectSource(scanConnection),
          VerifiedObjectCopier.randomPeer,
          stagingConnection,
          committedConnection,
          appConfig.backfilling.downloadParallelism,
          loggerFactory,
        ),
        backfillingProgress,
        new BulkStorageBackfilling.UpToFirstOwnSegment(historyStart, storageConfig),
        loggerFactory,
      )
    }

  val reader = new BulkStorageReader(
    acsStagingProgress,
    acsCommittedProgress,
    updatesStagingProgress,
    updatesCommittedProgress,
    storageConfig,
    stagingConnection,
    committedConnection,
    () => historyStart.get.map(_.map(_.firstOwnSegmentStart(storageConfig))),
    loggerFactory,
  )

  val acsStagingWriter = new AcsSnapshotBulkStorageWriterFromDb(
    storageConfig,
    appConfig,
    acsSnapshotStore,
    stagingConnection,
    historyMetrics,
    loggerFactory,
  )
  val acsStaging = new AcsSnapshotBulkStorage(
    "AcsSnapshotBulkStorageStaging",
    acsStagingWriter,
    acsStagingProgress,
    appConfig,
    backfillingCompleteGate,
    loggerFactory,
  )
  val acsCommittedWriter = new AcsSnapshotBulkStorageCommitFromStaging(
    stagingConnection,
    committedConnection,
    reader,
    appConfig,
    scanConnection,
    objs =>
      objs.foreach { obj =>
        val encoding = ScanStorageConfig.Encoding.all.toList
          .collectFirst {
            case enc if enc.storageKeyRegex("ACS").matches(obj.key) =>
              enc.key
          }
          .getOrElse("unknown")
        historyMetrics.BulkStorage.incAcsSnapshotObjects(encoding, "committed")
      },
    loggerFactory,
  )
  val acsCommitted = new AcsSnapshotBulkStorage(
    "AcsSnapshotBulkStorageCommitted",
    acsCommittedWriter,
    acsCommittedProgress,
    appConfig,
    backfillingCompleteGate,
    loggerFactory,
  )
  val updatesStagingWriter = new UpdateHistoryBulkStorageWriterFromDb(
    storageConfig,
    appConfig,
    updateHistory,
    stagingConnection,
    historyMetrics,
    currentMigrationId,
    loggerFactory,
  )
  val updatesStaging = new UpdateHistoryBulkStorage(
    "UpdateHistoryBulkStorageStaging",
    updatesStagingWriter,
    updatesStagingProgress,
    appConfig,
    backfillingCompleteGate,
    loggerFactory,
  )
  val updatesCommittedWriter = new UpdateHistoryBulkStorageCommitFromStaging(
    stagingConnection,
    committedConnection,
    reader,
    appConfig,
    scanConnection,
    objs =>
      objs.foreach { obj =>
        val encoding = ScanStorageConfig.Encoding.all.toList
          .collectFirst {
            case enc if enc.storageKeyRegex("updates").matches(obj.key) =>
              enc.key
          }
          .getOrElse("unknown")
        historyMetrics.BulkStorage.incUpdateObjects(encoding, "committed")
      },
    loggerFactory,
  )
  val updatesCommitted = new UpdateHistoryBulkStorage(
    "UpdateHistoryBulkStorageCommitted",
    updatesCommittedWriter,
    updatesCommittedProgress,
    appConfig,
    backfillingCompleteGate,
    loggerFactory,
  )

  // Services are only started once initialization has completed.
  private lazy val services =
    (backfilling.toList ++ Seq[PekkoRetryableService[?]](
      acsStaging,
      acsCommitted,
      updatesStaging,
      updatesCommitted,
    )).map(_.asPekkoRetryingService(automationConfig, backoffClock, retryProvider))

  private def initialize(): Future[BulkStorage] = {
    val resetAll =
      if (appConfig.debugForceStartFromGenesis) {
        logger.warn(
          "debugForceStartFromGenesis is set to true, resetting all bulk storage progress and starting from genesis"
        )
        for {
          _ <- acsStagingProgress.reset
          _ <- acsCommittedProgress.reset
          _ <- updatesStagingProgress.reset
          _ <- updatesCommittedProgress.reset
          _ <- backfillingProgress.resetCompletion()
        } yield ()
      } else Future.unit
    resetAll.map { _ =>
      services.discard
      this
    }
  }

  final override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    services.flatMap(_.closeAsync()) :+
      SyncCloseable("peer scan connection", LifeCycle.close(scanConnection)(logger))
}

object BulkStorage {

  val acsStagingKvStoreKey = "latest_acs_snapshot_in_bulk_storage_staging"
  val acsCommittedKvStoreKey = "latest_acs_snapshot_in_bulk_storage_committed"
  val updatesStagingKvStoreKey = "latest_updates_segment_in_bulk_storage_staging"
  val updatesCommittedKvStoreKey = "latest_updates_segment_in_bulk_storage_committed"
  val firstAcsSnapshotTimestampKvStoreKey = "first_acs_snapshot_timestamp_in_bulk_storage"

  def apply(
      storageConfig: ScanStorageConfig,
      appConfig: BulkStorageConfig,
      acsSnapshotStore: AcsSnapshotStore,
      updateHistory: UpdateHistory,
      currentMigrationId: Long,
      kvProvider: ScanKeyValueProvider,
      historyStart: ScanHistoryStart,
      metricsFactory: LabeledMetricsFactory,
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      store: ScanStore,
      svName: String,
      ledgerClient: SpliceLedgerClient,
      upgradesConfig: UpgradesConfig,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      actorSystem: ActorSystem,
      tc: TraceContext,
      ec: ExecutionContextExecutor,
      tracer: Tracer,
      httpClient: HttpClient,
      templateJsonDecoder: TemplateJsonDecoder,
  ): Future[BulkStorage] = {
    val logger = loggerFactory.getTracedLogger(classOf[BulkStorage])

    (appConfig.staging, appConfig.committed).tupled.fold {
      logger.debug("s3 connection not configured, not dumping to bulk storage")(tc)
      throw Status.FAILED_PRECONDITION
        .withDescription("S3 connection not configured, cannot initialize bulk storage")
        .asRuntimeException()
    } { case (stagingS3Config, committedS3Config) =>
      new BulkStorage(
        storageConfig,
        appConfig,
        stagingS3Config,
        committedS3Config,
        acsSnapshotStore,
        updateHistory,
        currentMigrationId,
        kvProvider,
        historyStart,
        metricsFactory,
        automationConfig,
        backoffClock,
        store,
        svName,
        ledgerClient,
        upgradesConfig,
        retryProvider,
        loggerFactory,
      ).initialize()
    }
  }
}
