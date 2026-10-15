// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.{
  HistoryMetrics,
  S3BucketConnection,
  TimestampWithMigrationId,
}

import scala.concurrent.{ExecutionContext, Future}
import cats.data.OptionT
import cats.implicits.*
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow

class AcsSnapshotBulkStorageWriterFromDb(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    acsSnapshotStore: AcsSnapshotStore,
    s3Connection: S3BucketConnection,
    historyMetrics: HistoryMetrics,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AcsSnapshotBulkStorageWriter
    with NamedLogging
    with Spanning {

  override def getNextSnapshotTimestampAfter(
      last: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[Option[TimestampWithMigrationId]] =
    OptionT(acsSnapshotStore.lookupSnapshotAfter(last.migrationId, last.timestamp)).subflatMap {
      snapshot =>
        if (snapshot.indexesCreated) {
          Some(TimestampWithMigrationId(snapshot.snapshotRecordTime, snapshot.migrationId))
        } else {
          logger.debug(s"Snapshot $snapshot is not yet indexed, so we cannot query it.")
          None
        }
    }.value

  override def shouldProcessSnapshotAt(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext
  ): Boolean = {
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

  override def processSnapshotsFlow(implicit
      tc: TraceContext,
      actorSystem: ActorSystem,
  ): Flow[TimestampWithMigrationId, TimestampWithMigrationId, NotUsed] = {
    SingleAcsSnapshotBulkStorage
      .asFlow(
        storageConfig,
        appConfig,
        acsSnapshotStore,
        s3Connection,
        historyMetrics,
        loggerFactory,
      )
      .map { case (ts, keys) =>
        logger.debug(
          s"Successfully dumped snapshot from migration ${ts.migrationId}, timestamp ${ts.timestamp} to bulk storage, with object keys: $keys"
        )
        ts
      }
  }

}
