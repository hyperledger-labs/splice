// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.store.{
  HistoryMetrics,
  PageLimit,
  S3BucketConnection,
  UpdateHistory,
}

import scala.concurrent.{ExecutionContext, Future}

class UpdateHistoryBulkStorageWriterFromDb(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    updateHistory: UpdateHistory,
    s3Connection: S3BucketConnection,
    historyMetrics: HistoryMetrics,
    val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends UpdateHistoryBulkStorageWriter
    with NamedLogging
    with Spanning {

  private def segmentAfter(start: CantonTimestamp): UpdatesSegment =
    UpdatesSegment(start, storageConfig.computeBulkSnapshotTimeAfter(start))

  /** Gets the very first updates segment for this network after genesis
    * May return None if unknown yet. This could happen if no updates have been ingested,
    * so we do not know the genesis record time yet. The caller should then schedule a retry.
    */
  private def getFirstSegmentFromGenesis(implicit
      tc: TraceContext
  ): Future[Option[UpdatesSegment]] =
    updateHistory
      .getUpdatesWithoutImportUpdates(None, PageLimit.tryCreate(1))
      .map(_.headOption.map { first =>
        UpdatesSegment(
          CantonTimestamp.MinValue,
          storageConfig.computeBulkSnapshotTimeAfter(first.update.update.recordTime),
        )
      })

  override def getNextSegmentAfter(
      afterO: Option[UpdatesSegment]
  )(implicit tc: TraceContext): Future[Option[UpdatesSegment]] =
    afterO match {
      case Some(previous) => Future.successful(Some(segmentAfter(previous.toTimestamp)))
      case None => getFirstSegmentFromGenesis
    }

  override def processSegmentsFlow(implicit
      tc: TraceContext
  ): Flow[UpdatesSegment, UpdatesSegment, NotUsed] = {
    UpdateHistorySegmentBulkStorage
      .asFlow(
        storageConfig,
        appConfig,
        updateHistory,
        s3Connection,
        historyMetrics,
        loggerFactory,
      )
      .map { case (segment, keys) =>
        logger.debug(
          s"Successfully dumped updates segment $segment to bulk storage, with object keys: $keys"
        )
        segment
      }
  }

}
