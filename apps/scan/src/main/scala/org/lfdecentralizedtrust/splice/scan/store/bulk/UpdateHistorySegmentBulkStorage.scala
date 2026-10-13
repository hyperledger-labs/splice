// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.apache.pekko.pattern.after
import org.lfdecentralizedtrust.splice.scan.admin.http.{ScanHttpEncodings, ScanJsonSupport}
import org.lfdecentralizedtrust.splice.store.{
  HistoryMetrics,
  PageLimit,
  S3BucketConnection,
  TimestampWithMigrationId,
  TreeUpdateWithMigrationId,
  UpdateHistory,
}
import io.circe.syntax.*
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.math.Ordering.Implicits.*

case class UpdatesSegment(
    fromTimestamp: TimestampWithMigrationId,
    toTimestamp: TimestampWithMigrationId,
)

/** Pekko source for dumping all updates from a segment to S3 objects.
  * Reads updates from the updateStore, encodes and compresses them
  * into chunks of size >=config.bulkZstdFrameSize. Each chunk is a frame
  * in zstd terms (i.e. a complete zstd object). The chunks are written into
  * s3 objects of size >=config.bulkMaxFileSize (as multi-frame zstd objects, which
  * are simply a concatenation of zstd objects), using multi-part upload (where
  * each chunk/frame is a part in the upload).
  * Whenever an object is fully written, the source emits an Output object
  * with the segment details, the name of the object just written (useful for monitoring
  * progress and testing), and a flag of whether this is the last object in this
  * segment (useful when streaming a sequence of segments, so that we can easily
  * know when each segment is complete).
  */
class UpdateHistorySegmentBulkStorage(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    updateHistory: UpdateHistory,
    s3Connection: S3BucketConnection,
    segment: UpdatesSegment,
    historyMetrics: HistoryMetrics,
    override val loggerFactory: NamedLoggerFactory,
)(implicit tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  private case class GetUpdatesResult(
      updates: Seq[TreeUpdateWithMigrationId],
      lastTimestamp: TimestampWithMigrationId,
      doneWithSegment: Boolean,
  )

  private def getUpdates(
      afterTs: TimestampWithMigrationId,
      limit: PageLimit,
  ): Future[GetUpdatesResult] = {
    for {
      updates <- updateHistory.getUpdatesWithoutImportUpdates(
        Some(TimestampWithMigrationId(afterTs.timestamp, afterTs.migrationId)),
        limit,
      )
      _ = logger.debug(
        s"Fetched ${updates.length} updates from record time ${afterTs.timestamp} (limit was $limit)"
      )
      updatesInSegment = updates.filter(update =>
        TimestampWithMigrationId(
          update.update.update.recordTime,
          update.migrationId,
        ) <= segment.toTimestamp
      )
      result <-
        if (updates.isEmpty) {
          logger.debug(
            s"No updates found after record time ${afterTs.timestamp} yet, but we don't know if we're done with the segment"
          )
          Future.successful(GetUpdatesResult(Seq.empty, afterTs, doneWithSegment = false))
        } else {
          if (updatesInSegment.nonEmpty) {
            logger.debug(
              s"Adding ${updatesInSegment.length} updates, between record time ${updatesInSegment.headOption
                  .map(_.update.update.recordTime)} and ${updatesInSegment.lastOption.map(_.update.update.recordTime)}"
            )
            val last = updatesInSegment.lastOption.getOrElse(
              throw new RuntimeException("Unexpected failure")
            )
            Future.successful(
              GetUpdatesResult(
                updatesInSegment,
                TimestampWithMigrationId(last.update.update.recordTime, last.migrationId),
                // If the query result contains updates outside the segment, then we know we're done with the segment
                doneWithSegment = updates.length > updatesInSegment.length,
              )
            )
          } else {
            // All updates are outside the segment, so we're done
            logger.debug(
              "No more updates inside the segment, done dumping updates from this segment"
            )
            Future.successful(GetUpdatesResult(Seq.empty, afterTs, doneWithSegment = true))
          }
        }
    } yield {
      result
    }
  }

  private def encodeUpdate(
      update: TreeUpdateWithMigrationId,
      encoding: ScanStorageConfig.Encoding,
  ): String = {
    logger.trace(
      s"encoding an update from DB with encoding ${encoding.key}, with timestamp ${update.update.update.recordTime}"
    )
    // Import custom encoders that omit null OmitNullString fields.
    // When we add new optional OmitNullString fields, they will be None until a coordinated
    // switching point.  The custom encoders ensure the null keys are absent from the JSON so that
    // SVs adopting a new version asynchronously do not break BFT guarantees.
    import ScanJsonSupport.*
    ScanHttpEncodings
      .encodeUpdateV2(
        update,
        encoding.damlValueEncoding,
        ScanHttpEncodings.V1,
      )
      .asJson
      .noSpacesSortKeys
  }

  private def updatesSource(implicit
      actorSystem: ActorSystem
  ): Source[Seq[TreeUpdateWithMigrationId], NotUsed] = {

    final case class State(
        afterTs: TimestampWithMigrationId,
        sleepBeforeNextFetch: Boolean,
        done: Boolean,
    )

    Source.unfoldAsync(State(segment.fromTimestamp, sleepBeforeNextFetch = false, done = false)) {
      state =>
        if (state.done) {
          logger.debug(
            s"Done dumping updates from segment ${segment.fromTimestamp}-${segment.toTimestamp}"
          )
          Future.successful(None)
        } else {
          def call(): Future[Option[(State, Seq[TreeUpdateWithMigrationId])]] =
            getUpdates(state.afterTs, PageLimit.tryCreate(appConfig.dbReadChunkSize))
              .map[Option[(State, Seq[TreeUpdateWithMigrationId])]] {
                case GetUpdatesResult(updates, nextTs, doneWithSegment) =>
                  Some(
                    (
                      State(
                        nextTs,
                        sleepBeforeNextFetch = updates.length < appConfig.dbReadChunkSize,
                        done = doneWithSegment,
                      ),
                      updates,
                    )
                  )
              }

          if (state.sleepBeforeNextFetch) {
            // Previous call did not return a full page (but did not reach the end of the segment yet),
            // so we sleep for a while before the next query
            after(appConfig.updatesPollingInterval.underlying, actorSystem.scheduler)(call())
          } else call()
        }
    }
  }

  private def getSource(implicit
      actorSystem: ActorSystem
  ): Source[Seq[String], NotUsed] = {
    updatesSource
      .map(updates => {
        historyMetrics.BulkStorage.incUpdatesCount(updates.length)
        updates
      })
      .mapConcat(identity)
      .via(
        MultiEncodingBulkStorageFlow(
          encodeUpdate,
          encoding =>
            // We use lazyFlow, so that in the case where no updates are emitted, we don't instantiate the S3ZstdObjects at all,
            // since it assumes that it gets at least one chunk to write.
            Flow.lazyFlow(() =>
              S3ZstdObjects(
                storageConfig,
                appConfig,
                s3Connection,
                objIdx =>
                  s"${storageConfig.getSegmentFolder(segment.fromTimestamp.timestamp, Some(segment.toTimestamp.timestamp))}/${encoding
                      .storageKey("updates", objIdx)}",
                loggerFactory,
              )
            ),
          encoding => historyMetrics.BulkStorage.incUpdateObjects(encoding.key, "staging"),
        )
      )
      .orElse(Source.lazySource { () =>
        logger.warn(s"No updates found in segment ${segment.fromTimestamp}-${segment.toTimestamp}")
        Source.empty
      })
      .fold(Seq.empty[String])(_ :+ _)
  }
}
object UpdateHistorySegmentBulkStorage {

  def asFlow(
      storageConfig: ScanStorageConfig,
      appConfig: BulkStorageConfig,
      updateHistory: UpdateHistory,
      s3Connection: S3BucketConnection,
      historyMetrics: HistoryMetrics,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): Flow[UpdatesSegment, (UpdatesSegment, Seq[String]), NotUsed] =
    Flow[UpdatesSegment].flatMapConcat { (segment: UpdatesSegment) =>
      new UpdateHistorySegmentBulkStorage(
        storageConfig,
        appConfig,
        updateHistory,
        s3Connection,
        segment,
        historyMetrics,
        loggerFactory,
      ).getSource.map(keys => (segment, keys))
    }

  def asSource(
      storageConfig: ScanStorageConfig,
      appConfig: BulkStorageConfig,
      updateHistory: UpdateHistory,
      s3Connection: S3BucketConnection,
      segment: UpdatesSegment,
      historyMetrics: HistoryMetrics,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): Source[Seq[String], NotUsed] =
    new UpdateHistorySegmentBulkStorage(
      storageConfig,
      appConfig,
      updateHistory,
      s3Connection,
      segment,
      historyMetrics,
      loggerFactory,
    ).getSource

}
