// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import UpdatesPosition.*
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.util.ByteString
import org.apache.pekko.pattern.after
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.scan.admin.http.ScanHttpEncodings
import org.lfdecentralizedtrust.splice.store.{HardLimit, UpdateHistory}
import io.circe.syntax.*
import org.apache.pekko.actor.ActorSystem

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

// TODO: some duplication between this and SingleAcsSnapshotBulkStorage, see if we can more nicely reuse stuff

object UpdatesPosition {
  sealed trait UpdatesPosition

  case object Start extends UpdatesPosition

  case object End extends UpdatesPosition

  final case class TS(migrationId: Long, timestamp: CantonTimestamp) extends UpdatesPosition
}
class UpdateHistorySegmentBulkStorageNew(
    val config: ScanStorageConfig,
    val updateHistory: UpdateHistory,
    val s3Connection: S3BucketConnection,
    val fromMigrationId: Long,
    val fromTimestamp: CantonTimestamp,
    val toMigrationId: Long,
    val toTimestamp: CantonTimestamp,
    override val loggerFactory: NamedLoggerFactory,
)(implicit tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  // When more updates are not yet available, how long to wait for more.
  // TODO(#3429): make it longer for prod (so consider making it configurable/overridable for tests)
  private val snapshotPollingInterval = 5.seconds

  private def getUpdatesChunk(
      afterMigrationId: Long,
      afterTimestamp: CantonTimestamp,
  )(implicit actorSystem: ActorSystem): Future[(UpdatesPosition, ByteString)] = {
    for {
      updates <- updateHistory.getUpdatesWithoutImportUpdates(
        Some((afterMigrationId, afterTimestamp)),
        HardLimit.tryCreate(config.bulkDbReadChunkSize),
      )
      updatesInSegment = updates.filter(update =>
        update.migrationId < toMigrationId ||
          update.migrationId == toMigrationId && update.update.update.recordTime <= toTimestamp
      )
    } yield {
      if (
        updatesInSegment.length < updates.length || updates.length == config.bulkDbReadChunkSize
      ) {
        if (updatesInSegment.nonEmpty) {
          logger.debug(s"Adding ${updatesInSegment.length} updates to the queue")
          val encoded = updatesInSegment.map(update =>
            ScanHttpEncodings.encodeUpdate(
              update,
              definitions.DamlValueEncoding.CompactJson,
              ScanHttpEncodings.V1,
            )
          )
          val updatesStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n") + "\n"
          val updatesBytes = ByteString(updatesStr.getBytes(StandardCharsets.UTF_8))
          logger.debug(
            s"Read ${encoded.length} updates from DB, to a bytestring of size ${updatesBytes.length} bytes"
          )
          val last = updatesInSegment.lastOption.getOrElse(throw new RuntimeException("Unexpected failure"))
          lastEmitted.set(
            Some((last.migrationId, last.update.update.recordTime))
          )
          (
            TS(last.migrationId, last.update.update.recordTime),
            updatesBytes,
          )
        } else {
          (End, ByteString.empty)
        }
      } else {
        logger.debug(
          s"Not enough updates yet (queried for ${config.bulkDbReadChunkSize}, found ${updates.length}), nothing to do"
        )
        // FIXME: sleep
        (TS(afterMigrationId, afterTimestamp), ByteString.empty)
      }
    }
  }
  private val s3ObjIdx = new AtomicInteger(0)
  private val lastEmitted = new AtomicReference[Option[(Long, CantonTimestamp)]](None)

  private def getSource: Source[(Long, CantonTimestamp), NotUsed] = {
    // FIXME: handle the case where no more updates exist yet (sleep before retrying, similarly to what we do between ACS snapshots (but not for a single snapshot))
    Source
      .unfoldAsync(Start: UpdatesPosition) {
        case Start => getUpdatesChunk(fromMigrationId, fromTimestamp).map(Some(_))
        case TS(migrationId, timestamp) => getUpdatesChunk(migrationId, timestamp).map(Some(_))
        case End => Future.successful(None)
      }
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
      .map(_ => lastEmitted.get().getOrElse((fromMigrationId, fromTimestamp)))

  }
}
object UpdateHistorySegmentBulkStorageNew {
  def asFlow(
      config: ScanStorageConfig,
      updateHistory: UpdateHistory,
      s3Connection: S3BucketConnection,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Flow[((Long, CantonTimestamp), (Long, CantonTimestamp)), (Long, CantonTimestamp), NotUsed] =
    Flow[((Long, CantonTimestamp), (Long, CantonTimestamp))].flatMapConcat {
      case (
            (fromMigrationId: Long, fromTimestamp: CantonTimestamp),
            (toMigrationId: Long, toTimestamp: CantonTimestamp),
          ) =>
        new UpdateHistorySegmentBulkStorageNew(
          config,
          updateHistory,
          s3Connection,
          fromMigrationId,
          fromTimestamp,
          toMigrationId,
          toTimestamp,
          loggerFactory,
        ).getSource
    }

  def asSource(
      config: ScanStorageConfig,
      updateHistory: UpdateHistory,
      s3Connection: S3BucketConnection,
      fromMigrationId: Long,
      fromTimestamp: CantonTimestamp,
      toMigrationId: Long,
      toTimestamp: CantonTimestamp,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Source[(Long, CantonTimestamp), NotUsed] =
    new UpdateHistorySegmentBulkStorageNew(
      config,
      updateHistory,
      s3Connection,
      fromMigrationId,
      fromTimestamp,
      toMigrationId,
      toTimestamp,
      loggerFactory,
    ).getSource

}
