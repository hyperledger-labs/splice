// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{OverflowStrategy, QueueOfferResult}
import org.apache.pekko.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.scan.admin.http.ScanHttpEncodings
import org.lfdecentralizedtrust.splice.store.{HardLimit, TreeUpdateWithMigrationId, UpdateHistory}

import java.nio.ByteBuffer
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}
import io.circe.syntax.*
import org.apache.pekko.Done

import java.nio.charset.StandardCharsets

class UpdateHistorySegmentBulkStorage(
    val config: BulkStorageConfig,
    val updateHistory: UpdateHistory,
    val s3Connection: S3BucketConnection,
    val fromMigrationId: Long,
    val fromTimestamp: CantonTimestamp,
    val toMigrationId: Long,
    val toTimestamp: CantonTimestamp,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  private val liveSource: Source[ByteString, SourceQueueWithComplete[ByteString]] =
    Source.queue[ByteString](2, overflowStrategy = OverflowStrategy.backpressure)
  private val (queue, initStream) = liveSource
    .toMat(Sink.asPublisher(fanout = false))(org.apache.pekko.stream.scaladsl.Keep.both)
    .run()
  private val s3ObjIdx = new AtomicInteger(0)
  private val position = new AtomicReference[Option[(Long, CantonTimestamp)]](None)

  val pipeline: Future[Done] = Source
    .fromPublisher(initStream)
    .via(ZstdGroupedWeight(config.maxFileSize))
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
    .runWith(Sink.ignore)

  private def encodeAndOfferToQueue(updates: Seq[TreeUpdateWithMigrationId]): Future[Unit] = {
    if (updates.nonEmpty) {
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
        s"Read ${encoded.length} updates from DB, to a bytestring of size ${updatesBytes.length} bytes"
      )
      queue.offer(updatesBytes).map {
        case QueueOfferResult.Enqueued => ()
        case QueueOfferResult.Dropped =>
          throw new RuntimeException(
            "Queue overflow. This should not happen with OverflowStrategy.backpressure"
          )
        case QueueOfferResult.Failure(ex) => throw ex
        case QueueOfferResult.QueueClosed => throw new RuntimeException("Queue closed")
      }
    } else Future.successful(())
  }

  /** Should be called repeatedly. Each call attempts to fetch `config.dbReadChunkSize` updates from the DB and push
    * them to the stream. Once the segment is finished, will close the stream and must not be called again
    *
    * @return
    *   Result.NotReady: not enough updates in the DB yet, did not push any data to the stream.
    *                    The caller should retry later (after some time, so that more updates will be ingested)
    *
    *   Result.NotDone:  Updates were read and pushed to the stream, but the segment has not finished yet.
    *                    More data could be available in the DB already, so the caller usually wants to call next() again immediately.
    *
    *   Result.Done:     Done reading and pushing updates to this segment. The pipeline is closed, and next() must not be called again
    *                    for this segment.
    */
  def next(): Future[Result.Result] = {
    for {
      updates <- position
        .get()
        .fold(
          updateHistory.getUpdatesWithoutImportUpdates(
            Some((fromMigrationId, fromTimestamp)),
            HardLimit.tryCreate(config.dbReadChunkSize),
            afterIsInclusive = true,
          )
        )(after =>
          updateHistory.getUpdatesWithoutImportUpdates(
            Some((after._1, after._2)),
            HardLimit.tryCreate(config.dbReadChunkSize),
            afterIsInclusive = false,
          )
        )

      // TODO(#3429): Figure out the < vs <= issue
      updatesInSegment = updates.filter(update =>
        update.migrationId < toMigrationId ||
          update.migrationId == toMigrationId && update.update.update.recordTime < toTimestamp
      )
      _ <-
        if (updatesInSegment.length < updates.length || updates.length == config.dbReadChunkSize) {
          logger.debug(s"Adding ${updatesInSegment.length} updates to the queue")
          encodeAndOfferToQueue(updatesInSegment)
        } else {
          logger.debug(
            s"Not enough updates yet (queried for ${config.dbReadChunkSize}, found ${updates.length}), nothing to do"
          )
          Future.successful(())
        }
    } yield {
      if (updatesInSegment.length < updates.length) {
        queue.complete()
        Result.Done
      } else if (updatesInSegment.length < config.dbReadChunkSize) {
        Result.NotReady
      } else {
        val last = updatesInSegment.lastOption.getOrElse(
          throw new RuntimeException("No updates added, unexpectedly")
        )
        position.set(Some((last.migrationId, last.update.update.recordTime)))
        Result.NotDone
      }
    }
  }

  def watchCompletion(): Future[Unit] =
    pipeline.map(_ => ())
}

case object Result {
  sealed trait Result
  case object Done extends Result
  case object NotDone extends Result
  case object NotReady extends Result
}
