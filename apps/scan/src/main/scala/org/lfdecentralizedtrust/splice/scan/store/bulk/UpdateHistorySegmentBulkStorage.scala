// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.apache.pekko.stream.OverflowStrategy
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

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.math.Ordering.Implicits.*

// TODO(#3429): some duplication between this and SingleAcsSnapshotBulkStorage, see if we can more nicely reuse stuff

class UpdateHistorySegmentBulkStorage(
    val config: ScanStorageConfig,
    val updateHistory: UpdateHistory,
    val s3Connection: S3BucketConnection,
    val fromTimestamp: TimestampWithMigrationId,
    val toTimestamp: TimestampWithMigrationId,
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
        TimestampWithMigrationId(update.update.update.recordTime, update.migrationId) <= toTimestamp
      )
      result <-
        if (
          updatesInSegment.length < updates.length || updates.length == config.bulkDbReadChunkSize
        ) {
          if (updatesInSegment.nonEmpty) {
            // Found enough updates to add
            val updatesBytes: ByteString = encodeUpdates(updatesInSegment)
            val last = updatesInSegment.lastOption.getOrElse(
              throw new RuntimeException("Unexpected failure")
            )
            lastEmitted.set(
              Some(TimestampWithMigrationId(last.update.update.recordTime, last.migrationId))
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
            Future.successful(None)
          }
        } else {
          logger.debug(
            s"Not enough updates yet (queried for ${config.bulkDbReadChunkSize}, found ${updates.length}), sleeping..."
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
      s"Read and encoded ${encoded.length} updates from DB, to a bytestring of size ${updatesBytes.length} bytes"
    )
    updatesBytes
  }

  private val s3ObjIdx = new AtomicInteger(0)
  private val lastEmitted = new AtomicReference[Option[TimestampWithMigrationId]](None)

  private def getSource(implicit
      actorSystem: ActorSystem
  ): Source[TimestampWithMigrationId, NotUsed] = {
    Source
      .unfoldAsync(fromTimestamp)(ts => getUpdatesChunk(ts))
      .via(ZstdGroupedWeight(config.bulkMaxFileSize))
      // Add a buffer so that the next object continues accumulating while we write the previous one
      .buffer(
        1,
        OverflowStrategy.backpressure,
      )
      .mapAsync(1) { case ByteStringWithTermination(zstdObj, isLast) =>
        val objectKey = if (isLast) s"updates_${s3ObjIdx}_last.zstd" else s"updates_$s3ObjIdx.zstd"
        // TODO(#3429): For now, we accumulate the full object in memory, then write it as a whole.
        //    Consider streaming it to S3 instead. Need to make sure that it then handles crashes correctly,
        //    i.e. that until we tell S3 that we're done writing, if we stop, then S3 throws away the
        //    partially written object.
        for {
          _ <- s3Connection.writeFullObject(objectKey, ByteBuffer.wrap(zstdObj.toArrayUnsafe()))
        } yield {
          s3ObjIdx.addAndGet(1)
          ()
        }
      }
      // emit a Unit upon completion of the write to s3
      .fold(()) { case ((), _) => () }
      // emit the timestamp of the last update dumped upon completion.
      .map(_ => lastEmitted.get().getOrElse(fromTimestamp))

  }
}
object UpdateHistorySegmentBulkStorage {
  def asFlow(
      config: ScanStorageConfig,
      updateHistory: UpdateHistory,
      s3Connection: S3BucketConnection,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): Flow[(TimestampWithMigrationId, TimestampWithMigrationId), TimestampWithMigrationId, NotUsed] =
    Flow[(TimestampWithMigrationId, TimestampWithMigrationId)].flatMapConcat {
      case (
            from: TimestampWithMigrationId,
            to: TimestampWithMigrationId,
          ) =>
        new UpdateHistorySegmentBulkStorage(
          config,
          updateHistory,
          s3Connection,
          from,
          to,
          loggerFactory,
        ).getSource
    }

  def asSource(
      config: ScanStorageConfig,
      updateHistory: UpdateHistory,
      s3Connection: S3BucketConnection,
      from: TimestampWithMigrationId,
      to: TimestampWithMigrationId,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): Source[TimestampWithMigrationId, NotUsed] =
    new UpdateHistorySegmentBulkStorage(
      config,
      updateHistory,
      s3Connection,
      from,
      to,
      loggerFactory,
    ).getSource

}
