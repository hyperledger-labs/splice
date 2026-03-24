// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.pattern.after
import org.lfdecentralizedtrust.splice.PekkoRetryingService
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.bulk.AcsSnapshotBulkStorage.AcsSnapshotObjects
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{HardLimit, HistoryMetrics, Limit, S3BucketConnection, TimestampWithMigrationId, UpdateHistory}

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
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning {

  private def getStartTimestamp(implicit
      tc: TraceContext
  ): Future[Option[TimestampWithMigrationId]] =
    kvProvider.getLatestAcsSnapshotInBulkStorage().value

  private def getAcsSnapshotTimestampsAfter(
      start: TimestampWithMigrationId
  )(implicit tc: TraceContext): Source[TimestampWithMigrationId, NotUsed] = {
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
  private def mksrc()(implicit tc: TraceContext): Source[TimestampWithMigrationId, Cancellable] = {

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
        .filter { ts =>
          val ret = storageConfig.shouldDumpSnapshotToBulkStorage(ts.timestamp)
          if (ret) {
            logger.debug(s"Dumping snapshot at timestamp ${ts.timestamp} to bulk storage")
          } else {
            logger.info(
              s"Skipping snapshot at timestamp ${ts.timestamp} for bulk storage, not required per the configured period of ${storageConfig.bulkAcsSnapshotPeriodHours}"
            )
          }
          ret
        }
        .flatMapConcat(ts =>
          Source
            .single(ts)
            .via(
              SingleAcsSnapshotBulkStorage
                .asFlow(
                  storageConfig,
                  appConfig,
                  acsSnapshotStore,
                  s3Connection,
                  historyMetrics,
                  loggerFactory,
                )
                .map(keys => {
                  logger.debug(
                    s"Successfully dumped snapshot from migration ${ts.migrationId}, timestamp ${ts.timestamp} to bulk storage, with object keys: $keys"
                  )
                  ts
                })
            )
        )
        .mapAsync(1) { ts =>
          historyMetrics.BulkStorage.latestAcsSnapshot.updateValue(ts.timestamp)
          kvProvider
            .setLatestAcsSnapshotsInBulkStorage(ts)
            .map(_ => {
              logger.info(
                s"Successfully completed dumping snapshots from migration ${ts.migrationId}, timestamp ${ts.timestamp}"
              )
              ts
            })
        }
    }
  }

  def asRetryableService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[TimestampWithMigrationId] = {
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
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
  }

  def getAcsSnapshotAtOrBefore(
      atOrBeforeTimestamp: CantonTimestamp
  )(implicit tc: TraceContext): Future[AcsSnapshotObjects] = {

    for {
      snapshotTs <- kvProvider
        .getLatestAcsSnapshotInBulkStorage()
        .value
        .map {
          case None =>
            throw Status.NOT_FOUND
              .withDescription("no snapshot in bulk storage yet")
              .asRuntimeException()
          case Some(ts) if ts.timestamp < atOrBeforeTimestamp =>
            logger.trace(
              s"Latest snapshot in bulk storage is at ${ts.timestamp}, which is before the requested timestamp ${atOrBeforeTimestamp}, returning that one"
            )
            ts.timestamp
          case Some(ts) => storageConfig.computeBulkSnapshotTimeAtOrBefore(atOrBeforeTimestamp)
        }
      prefix = storageConfig.findSegmentFolderPrefixByStartTimestamp(snapshotTs)
      objects <- s3Connection
        // A single object currently holds ~700K contracts, we apply a Limit just for safety,
        // but we don't expect to get anywhere near 1000 such objects in the foreseeable future
        // (hence the HardLimit, just as a safety precaution).
        .listObjects(
          prefix,
          _.matches(".*ACS_\\d+\\.zstd"),
          HardLimit.tryCreate(Limit.DefaultMaxPageSize))
    } yield {
      if (objects.isEmpty) {
        throw Status.NOT_FOUND
          .withDescription(
            s"No snapshot objects found in bulk storage at expected timestamp at or before $atOrBeforeTimestamp, this may be because the timestamp is before network genesis"
          )
          .asRuntimeException()
      }
      logger.trace(
        s"Found snapshot in bulk storage at timestamp $snapshotTs, with objects: ${objects.map(_.key).mkString(", ")}"
      )
      AcsSnapshotObjects(snapshotTs, objects)
    }
  }

}

object AcsSnapshotBulkStorage {
  case class AcsSnapshotObjects(
      timestamp: CantonTimestamp,
      objects: Seq[ObjectKeyAndChecksum],
  )
}
