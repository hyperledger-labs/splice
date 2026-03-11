// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.pattern.after
import org.lfdecentralizedtrust.splice.PekkoRetryingService
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, S3BucketConnection, TimestampWithMigrationId, UpdateHistory}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class AcsSnapshotBulkStorage(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    acsSnapshotStore: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    s3Connection: S3BucketConnection,
    kvProvider: ScanKeyValueProvider,
    historyMetrics: HistoryMetrics,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  private def getStartTimestamp: Future[Option[TimestampWithMigrationId]] =
    kvProvider.getLatestAcsSnapshotInBulkStorage().value

  private def getAcsSnapshotTimestampsAfter(
      start: TimestampWithMigrationId
  ): Source[TimestampWithMigrationId, NotUsed] = {
    Source
      .unfoldAsync(start) { (last: TimestampWithMigrationId) =>
        acsSnapshotStore.lookupSnapshotAfter(last.migrationId, last.timestamp).flatMap {
          case Some(snapshot) =>
            logger.info(
              s"next snapshot available, at migration ${snapshot.migrationId}, record time ${snapshot.snapshotRecordTime}"
            )
            Future.successful(
              Some(
                (
                  TimestampWithMigrationId(snapshot.snapshotRecordTime, snapshot.migrationId),
                  Some(TimestampWithMigrationId(snapshot.snapshotRecordTime, snapshot.migrationId)),
                )
              )
            )
          case None =>
            logger.debug("No new snapshot available, sleeping...")
            after(
              appConfig.snapshotPollingInterval.underlying,
              actorSystem.scheduler,
            ) {
              Future.successful(Some((last, None)))
            }
        }
      }
      .collect { case Some(ts) => ts }
  }

  /**  This is the main implementation of the pipeline. It is a Pekko Source that reads a `start` timestamp
    *   from the DB, and starts dumping to S3 all snapshots (strictly) after `start`. After every snapshot that
    *   is successfully dumped, it persists to the DB its timestamp, and emits that timestamp as an output.
    *   It is an infinite source that should never complete.
    */
  private def mksrc(): Source[TimestampWithMigrationId, Cancellable] = {

    // Wait for update history to initialize and for history backfilling to complete before starting bulk storage dumps
    val backfillingCompleteGate =
      Source
        .tick(0.seconds, appConfig.snapshotPollingInterval.underlying, ())
        .mapAsync(1)(_ =>
          if (updateHistory.isReady)
            updateHistory.isHistoryBackfilled(acsSnapshotStore.currentMigrationId)
          else Future.successful(false)
        )
        .filter(identity)
        .take(1)

    backfillingCompleteGate.flatMap { _ =>
      Source
        .future(getStartTimestamp)
        .flatMapConcat {
          case Some(start: TimestampWithMigrationId) =>
            logger.info(
              s"Latest dumped snapshot was from migration ${start.migrationId}, timestamp ${start.timestamp}"
            )
            getAcsSnapshotTimestampsAfter(start)
          case None =>
            logger.info("No dumped snapshots yet, starting from genesis")
            getAcsSnapshotTimestampsAfter(TimestampWithMigrationId(CantonTimestamp.MinValue, 0))
        }
        .filter { case TimestampWithMigrationId(ts, _) =>
          val ret = storageConfig.shouldDumpSnapshotToBulkStorage(ts)
          if (ret) {
            logger.debug(s"Dumping snapshot at timestamp $ts to bulk storage")
          } else {
            logger.info(
              s"Skipping snapshot at timestamp $ts for bulk storage, not required per the configured period of ${storageConfig.bulkAcsSnapshotPeriodHours}"
            )
          }
          ret
        }
        .via(
          SingleAcsSnapshotBulkStorage.asFlow(
            storageConfig,
            appConfig,
            acsSnapshotStore,
            s3Connection,
            historyMetrics,
            loggerFactory,
          )
        )
        .mapAsync(1) { (ts: TimestampWithMigrationId) =>
          historyMetrics.BulkStorage.latestAcsSnapshot.updateValue(ts.timestamp)
          for {
            _ <- kvProvider.setLatestAcsSnapshotsInBulkStorage(ts)
          } yield {
            logger.info(
              s"Successfully completed dumping snapshots from migration ${ts.migrationId}, timestamp ${ts.timestamp}"
            )
            ts
          }
        }
    }
  }

  def asRetryableService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[TimestampWithMigrationId] = {
    val src = mksrc()
    new PekkoRetryingService(
      src,
      Sink.ignore,
      automationConfig,
      backoffClock,
      "ACS Snapshot Bulk Storage",
      retryProvider,
      loggerFactory,
    )
  }

  def getAcsSnapshotAtOrBefore(atOrBeforeTimestamp: CantonTimestamp): Future[Seq[ObjectKeyAndChecksum]] = {
    val snapshotTimestamp = storageConfig.computeBulkSnapshotTimeAtOrBefore(atOrBeforeTimestamp)
    val prefix = storageConfig.findSegmentFolderPrefixByStartTimestamp(snapshotTimestamp)
    kvProvider
      .getLatestAcsSnapshotInBulkStorage()
      .value
      .map(_.fold(false)(_.timestamp >= snapshotTimestamp))
      .flatMap {
        case false =>
          throw new NoSuchElementException("bulk storage not caught up to the requested date")
        case true =>
          for {
            objects <- s3Connection.listObjectsWithChecksums(Some(prefix))
            filteredObjects = objects.filter(_.key.matches(".*ACS_\\d+\\.zstd")).toSeq
          } yield {
            filteredObjects
          }
      }
  }
}
