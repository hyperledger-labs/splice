// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import scala.concurrent.ExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{ErrorUtil, PekkoUtil}
import com.digitalasset.canton.util.PekkoUtil.{RetrySourcePolicy, WithKillSwitch}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{KillSwitch, KillSwitches, OverflowStrategy}
import org.apache.pekko.stream.scaladsl.{Keep, Source}
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
import Position.*
import org.apache.pekko.Done

object Position {
  sealed trait Position

  case object Start extends Position

  case object End extends Position

  final case class Index(value: Long) extends Position
}

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
        HardLimit.tryCreate(config.dbReadChunkSize),
        Seq.empty,
        Seq.empty,
      )
    } yield {
      val encoded = snapshot.createdEventsInPage.map(event =>
        CompactJsonScanHttpEncodings().javaToHttpCreatedEvent(event.eventId, event.event)
      )
      val contractsStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n") + "\n"
      val contractsBytes = ByteString(contractsStr.getBytes(StandardCharsets.UTF_8))
      logger.debug(
        s"Read ${encoded.length} contracts from ACS, to a bytestring of size ${contractsBytes.length} bytes"
      )
      (snapshot.afterToken.fold(End: Position)(Index(_)), contractsBytes)
    }

  }

  private[bulk] def getSingleAcsSnapshotDumpSource(
      migrationId: Long,
      timestamp: CantonTimestamp,
  ): Source[WithKillSwitch[Int], (KillSwitch, Future[Done])] = {

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
        .mapAsync(1) { case ByteStringWithTermination(zstdObj, isLast) =>
          val objectKey = if (isLast) s"snapshot_${idx}_last.zstd" else s"snapshot_$idx.zstd"
          // TODO(#3429): For now, we accumulate the full object in memory, then write it as a whole.
          //    Consider streaming it to S3 instead. Need to make sure that it then handles crashes correctly,
          //    i.e. that until we tell S3 that we're done writing, if we stop, then S3 throws away the
          //    partially written object.
          for {
            _ <- s3Connection.writeFullObject(objectKey, ByteBuffer.wrap(zstdObj.toArrayUnsafe()))
          } yield {
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
        lastFailure.map { t =>
          logger.warn(s"Writing ACS snapshot to bulk storage failed with : ${ErrorUtil
              .messageWithStacktrace(t)}, will retry after delay of ${delay}")
          // Always retry (TODO(#3429): consider a max number of retries?)
          delay -> ()
        }
      }
    }

    PekkoUtil
      .restartSource(
        name = "acs-snapshot-dump",
        initial = (),
        mkSource = (_: Unit) => mksrc,
        policy = policy,
      )
  }
}
