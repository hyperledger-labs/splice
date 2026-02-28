// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import scala.concurrent.ExecutionContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  HistoryMetrics,
  S3BucketConnection,
  TimestampWithMigrationId,
}

import scala.concurrent.Future
import io.circe.syntax.*

import java.nio.charset.StandardCharsets
import Position.*
import org.apache.pekko.NotUsed
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}

object Position {
  sealed trait Position

  case object Start extends Position

  case object End extends Position

  final case class Index(value: Long) extends Position
}

class SingleAcsSnapshotBulkStorage(
    timestamp: TimestampWithMigrationId,
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    acsSnapshotStore: AcsSnapshotStore,
    s3Connection: S3BucketConnection,
    historyMetrics: HistoryMetrics,
    override val loggerFactory: NamedLoggerFactory,
)(implicit tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  private def getAcsSnapshotChunk(
      timestamp: TimestampWithMigrationId,
      after: Option[Long],
  ): Future[(Position, ByteString)] = {
    for {
      snapshot <- acsSnapshotStore.queryAcsSnapshot(
        timestamp.migrationId,
        snapshot = timestamp.timestamp,
        after,
        HardLimit.tryCreate(storageConfig.bulkDbReadChunkSize),
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

  private def getSource: Source[TimestampWithMigrationId, NotUsed] = {
    Source
      .unfoldAsync(Start: Position) {
        case Start => getAcsSnapshotChunk(timestamp, None).map(Some(_))
        case Index(i) => getAcsSnapshotChunk(timestamp, Some(i)).map(Some(_))
        case End => Future.successful(None)
      }
      .via(
        S3ZstdObjects(
          storageConfig,
          appConfig,
          s3Connection,
          { objIdx => s"${storageConfig.getSegmentKeyPrefix(timestamp, None)}/ACS_$objIdx.zstd" },
          loggerFactory,
        )
      )
      .wireTap(_ => historyMetrics.BulkStorage.incAcsSnapshotObjects())
      // emit back the timestamp w. migrationId upon completion
      .collect { case S3ZstdObjects.Output(_, isLast) if isLast => timestamp }

  }
}

object SingleAcsSnapshotBulkStorage {

  /** Returns a Pekko flow that receives (timestamp, migration) elements, each identifying an ACS snapshot to be dumped,
    * and dumps each corresponding snapshot to the S3 storage. Every successful dump emits back the (timestamp, migration)
    * pair, to indicate the last successfully dumped snapshot.
    */
  def asFlow(
      storageConfig: ScanStorageConfig,
      appConfig: BulkStorageConfig,
      acsSnapshotStore: AcsSnapshotStore,
      s3Connection: S3BucketConnection,
      historyMetrics: HistoryMetrics,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Flow[TimestampWithMigrationId, TimestampWithMigrationId, NotUsed] =
    Flow[TimestampWithMigrationId].flatMapConcat {
      new SingleAcsSnapshotBulkStorage(
        _,
        storageConfig,
        appConfig,
        acsSnapshotStore,
        s3Connection,
        historyMetrics,
        loggerFactory,
      ).getSource
    }

  /** The same flow as a source, currently used only for unit testing.
    */
  def asSource(
      timestamp: TimestampWithMigrationId,
      storageConfig: ScanStorageConfig,
      appConfig: BulkStorageConfig,
      acsSnapshotStore: AcsSnapshotStore,
      s3Connection: S3BucketConnection,
      historyMetrics: HistoryMetrics,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Source[TimestampWithMigrationId, NotUsed] =
    new SingleAcsSnapshotBulkStorage(
      timestamp,
      storageConfig,
      appConfig,
      acsSnapshotStore,
      s3Connection,
      historyMetrics,
      loggerFactory,
    ).getSource

}
