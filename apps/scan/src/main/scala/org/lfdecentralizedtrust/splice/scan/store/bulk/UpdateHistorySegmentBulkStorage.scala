// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.apache.pekko.util.ByteString
import org.apache.pekko.pattern.after
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.scan.admin.http.ScanHttpEncodings
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  TimestampWithMigrationId,
  TreeUpdateWithMigrationId,
  UpdateHistory,
}
import io.circe.syntax.*
import org.apache.pekko.actor.ActorSystem

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.math.Ordering.Implicits.*

// TODO(#3429): some duplication between this and SingleAcsSnapshotBulkStorage, see if we can more nicely reuse stuff

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
    val config: ScanStorageConfig,
    val updateHistory: UpdateHistory,
    val s3Connection: S3BucketConnection,
    val segment: UpdatesSegment,
    override val loggerFactory: NamedLoggerFactory,
)(implicit tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  // When more updates are not yet available, how long to wait for more.
  // TODO(#3429): make it longer for prod (so consider making it configurable/overridable for tests)
  private val updatesPollingInterval = 5.seconds

  private def getUpdatesChunk(
      afterTs: TimestampWithMigrationId
  )(implicit actorSystem: ActorSystem): Future[Option[(TimestampWithMigrationId, ByteString)]] = {
    for {
      updates <- updateHistory.getUpdatesWithoutImportUpdates(
        Some((afterTs.migrationId, afterTs.timestamp)),
        HardLimit.tryCreate(config.bulkDbReadChunkSize),
      )
      updatesInSegment = updates.filter(update =>
        TimestampWithMigrationId(
          update.update.update.recordTime,
          update.migrationId,
        ) <= segment.toTimestamp
      )
      result <-
        if (
          updatesInSegment.length < updates.length || updates.length == config.bulkDbReadChunkSize
        ) {
          if (updatesInSegment.nonEmpty) {
            // Found enough updates to add
            logger.debug(
              s"Adding ${updatesInSegment.length} updates, between record time ${updatesInSegment.headOption
                  .map(_.update.update.recordTime)} and ${updatesInSegment.lastOption.map(_.update.update.recordTime)}"
            )
            val updatesBytes: ByteString = encodeUpdates(updatesInSegment)
            val last = updatesInSegment.lastOption.getOrElse(
              throw new RuntimeException("Unexpected failure")
            )
            Future.successful(
              Some(
                (
                  TimestampWithMigrationId(last.update.update.recordTime, last.migrationId),
                  updatesBytes,
                )
              )
            )
          } else {
            // All updates are outside the segment, so we're done
            logger.debug(
              "No more updates inside the segment, done dumping updates from this segment"
            )
            Future.successful(None)
          }
        } else {
          logger.debug(
            s"Not enough updates yet (queried for ${config.bulkDbReadChunkSize}, found ${updates.length}. Last update is from ${updates.lastOption
                .map(_.update.update.recordTime)}, migration ${updates.lastOption.map(_.migrationId)}), sleeping..."
          )
          after(updatesPollingInterval, actorSystem.scheduler) {
            Future.successful(Some((afterTs, ByteString.empty)))
          }
        }
    } yield {
      result
    }
  }

  private def encodeUpdates(updates: Seq[TreeUpdateWithMigrationId]) = {
    val encoded = updates.map(update =>
      ScanHttpEncodings.encodeUpdate(
        update,
        definitions.DamlValueEncoding.CompactJson,
        ScanHttpEncodings.V1,
      )
    )
    val updatesStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n") + "\n"
    val updatesBytes = ByteString(updatesStr.getBytes(StandardCharsets.UTF_8))
    logger.debug(
      s"Read and encoded ${encoded.length} updates from DB, to a bytestring of size ${updatesBytes.length} bytes. Timestamps are ${updates.headOption
          .map(_.update.update.recordTime)} to ${updates.lastOption.map(_.update.update.recordTime)}"
    )
    updatesBytes
  }

  private case class State(
      obj: s3Connection.AppendWriteObject,
      s3ObjIdx: Int,
      s3ObjSize: Int,
  )
  private case class ObjectChunk(
      bytes: ByteString,
      obj: s3Connection.AppendWriteObject,
      isLastChunkInObject: Boolean,
      isLastObjectInSegment: Boolean,
      partNumber: Int,
  )

  private def getSource(implicit
      actorSystem: ActorSystem
  ): Source[UpdateHistorySegmentBulkStorage.Output, NotUsed] = {
    Source
      .unfoldAsync(segment.fromTimestamp)(ts => getUpdatesChunk(ts))
      .via(
        S3ZstdObjects(
          config,
          s3Connection,
          { objIdx => s"${segment.fromTimestamp}-${segment.toTimestamp}/updates_$objIdx.zstd" },
          loggerFactory,
        )
      )
      .map((o: S3ZstdObjects.Output) =>
        UpdateHistorySegmentBulkStorage.Output(segment, o.objectKey, o.isLastObject)
      )
  }
}
object UpdateHistorySegmentBulkStorage {

  case class Output(
      segment: UpdatesSegment,
      objectKey: String,
      isLastObjectInSegment: Boolean,
  )

  def asFlow(
      config: ScanStorageConfig,
      updateHistory: UpdateHistory,
      s3Connection: S3BucketConnection,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): Flow[UpdatesSegment, Output, NotUsed] =
    Flow[UpdatesSegment].flatMapConcat { (segment: UpdatesSegment) =>
      new UpdateHistorySegmentBulkStorage(
        config,
        updateHistory,
        s3Connection,
        segment,
        loggerFactory,
      ).getSource
    }

  def asSource(
      config: ScanStorageConfig,
      updateHistory: UpdateHistory,
      s3Connection: S3BucketConnection,
      segment: UpdatesSegment,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): Source[Output, NotUsed] =
    new UpdateHistorySegmentBulkStorage(
      config,
      updateHistory,
      s3Connection,
      segment,
      loggerFactory,
    ).getSource

}
