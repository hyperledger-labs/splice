// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import scala.concurrent.ExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{ErrorUtil, PekkoUtil}
import com.digitalasset.canton.util.PekkoUtil.RetrySourcePolicy
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{KillSwitch, KillSwitches, OverflowStrategy}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.HardLimit

import scala.concurrent.Future
import io.circe.syntax.*

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.FiniteDuration

case class BulkStorageConfig(
    dbReadChunkSize: Int,
    maxFileSize: Long,
)

object BulkStorageConfigs {
  val bulkStorageConfigV1 = BulkStorageConfig(
    1000,
    (64 * 1024 * 1024).toLong,
  )
}

sealed trait Position
case object Start extends Position
case object End extends Position
final case class Index(value: Long) extends Position

class AcsSnapshotBulkStorage(
    val config: BulkStorageConfig,
    val acsSnapshotStore: AcsSnapshotStore,
    val s3Connection: S3BucketConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  private def getAcsSnapshotChunk(
      migrationId: Long,
      timestamp: CantonTimestamp,
      after: Option[Long],
  ): Future[(Position, ByteString)] = {
    for {
      snapshot <- acsSnapshotStore.queryAcsSnapshot(
        migrationId,
        snapshot = timestamp,
        after,
        limit = HardLimit.tryCreate(config.dbReadChunkSize),
        Seq.empty,
        Seq.empty,
      )
    } yield {
      val encoded = snapshot.createdEventsInPage.map(event =>
        CompactJsonScanHttpEncodings.javaToHttpCreatedEvent(event.eventId, event.event)
      )
      val contractsStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n") + "\n"
      val contractsBytes = ByteString(contractsStr.getBytes(StandardCharsets.UTF_8))
      logger.debug(
        s"Read ${encoded.length} contracts from ACS, to a bytestring of size ${contractsBytes.length} bytes"
      )
      (snapshot.afterToken.fold(End: Position)(Index(_)), contractsBytes)
    }

  }

  def dumpAcsSnapshot(migrationId: Long, timestamp: CantonTimestamp): Future[Unit] = {

    // TODO(#3429): currently, if this crashes half-way through, there is no indication in the S3 objects that
    //  the snapshot is incomplete. We probably want to label the last object with `last` or something like that
    //  so that we can detect incomplete snapshots and recreate them.

    def mksrc = {
      val idx = new AtomicInteger(0)
      val base = Source
        .unfoldAsync(Start: Position) {
          case Start => getAcsSnapshotChunk(migrationId, timestamp, None).map(Some(_))
          case Index(i) => getAcsSnapshotChunk(migrationId, timestamp, Some(i)).map(Some(_))
          case End => Future.successful(None)
        }
        .via(ZstdGroupedWeight(config.maxFileSize))
        // Add a buffer so that the next object continues accumulating while we write the previous one
        .buffer(
          1,
          OverflowStrategy.backpressure,
        )
        .mapAsync(1) { zstdObj =>
          val objectKey = s"snapshot_$idx.zstd"
          Future {
            // TODO(#3429): For now, we accumulate the full object in memory, then write it as a whole.
            //    Consider streaming it to S3 instead. Need to make sure that it then handles crashes correctly,
            //    i.e. that until we tell S3 that we're done writing, if we stop, then S3 throws away the
            //    partially written object.
            // TODO(#3429): Error handling
            val _ =
              s3Connection.writeFullObject(objectKey, ByteBuffer.wrap(zstdObj.toArrayUnsafe()))
            idx.addAndGet(1)
          }
        }
      val withKs = base.viaMat(KillSwitches.single)(Keep.right)
      withKs.watchTermination() { case (ks, done) => (ks: KillSwitch, done) }
    }

    // TODO(#3429): tweak the retry parameters here
    val delay = FiniteDuration(5, "seconds")
    val policy = new RetrySourcePolicy[Unit, Int] {
      override def shouldRetry(
          lastState: Unit,
          lastEmittedElement: Option[Int],
          lastFailure: Option[Throwable],
      ): Option[(scala.concurrent.duration.FiniteDuration, Unit)] = {
        val prefixMsg =
          s"RetrySourcePolicy for restart of AcsSnapshotBulkStorage with ${delay} delay:"
        lastFailure match {
          case Some(t) =>
            logger.info(s"$prefixMsg Last failure: ${ErrorUtil.messageWithStacktrace(t)}")
          case None =>
            logger.debug(s"$prefixMsg No failure, normal restart.")
        }
        // Always retry (TODO(#3429): consider a max number of retries?)
        Some(delay -> ())
      }
    }

    PekkoUtil
      .restartSource(
        name = "acs-snapshot-dump",
        initial = (),
        mkSource = (_: Unit) => mksrc,
        policy = policy,
      )
      .runWith(Sink.ignore)

  }.map(_ => ())
}
