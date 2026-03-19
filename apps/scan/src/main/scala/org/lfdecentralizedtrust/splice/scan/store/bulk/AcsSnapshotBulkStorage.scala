// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
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
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
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

  // TODO(#3429): we probably wanna move this and the pipeline below to S3BucketConnection,
  //  to reuse with the updates workflow (but there we'll need to be careful with the size of the return)
  //  we'll do that in the next PR, when we add support for querying the bulk storage for updates as well
  case class ObjectKeyAndChecksum(
      key: String,
      checksum: String,
  )

  def getAcsSnapshotAtOrBefore(
      atOrBeforeTimestamp: CantonTimestamp
  ): Future[(CantonTimestamp, Seq[(String, String)])] = {

    for {
      snapshotTs <- kvProvider
        .getLatestAcsSnapshotInBulkStorage()
        .value
        .map {
          case None =>
            throw Status.NOT_FOUND.withDescription("no snapshot in bulk storage yet").asRuntimeException()
          case Some(ts) if ts.timestamp < atOrBeforeTimestamp =>
            logger.trace(
              s"Latest snapshot in bulk storage is at ${ts.timestamp}, which is before the requested timestamp ${atOrBeforeTimestamp}, returning that one"
            )
            ts.timestamp
          case Some(ts) => storageConfig.computeBulkSnapshotTimeAtOrBefore(atOrBeforeTimestamp)
        }
      prefix = storageConfig.findSegmentFolderPrefixByStartTimestamp(snapshotTs)
      objects <- s3Connection
        .listObjectsSource(prefix)
        .filter(_.key.matches(".*ACS_\\d+\\.zstd"))
        .mapAsync(4) { obj => // TODO(#3429): make this parallelism configurable
          s3Connection.readChecksum(obj.key).map(checksum => obj.key -> checksum)
        }
        .runWith(Sink.seq[(String, String)])

    } yield {
      if (objects.isEmpty) {
        throw Status.NOT_FOUND.withDescription(
          s"No snapshot objects found in bulk storage at expected timestamp at or before $atOrBeforeTimestamp, this may be because the timestamp is before network genesis"
        ).asRuntimeException()
      }
      logger.trace(
        s"Found snapshot in bulk storage at timestamp ${snapshotTs}, with objects: ${objects.map(_._1)}"
      )
      (snapshotTs, objects) // FIXME: put in a nicer case class
    }
  }

}
