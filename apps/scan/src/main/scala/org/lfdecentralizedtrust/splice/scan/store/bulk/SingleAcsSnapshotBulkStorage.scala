// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import scala.concurrent.ExecutionContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.lfdecentralizedtrust.splice.scan.admin.http.ScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.{
  HistoryMetrics,
  PageLimit,
  S3BucketConnection,
  TimestampWithMigrationId,
}
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent

import scala.concurrent.Future
import io.circe.syntax.*

import Position.*
import org.apache.pekko.NotUsed
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}

object Position {
  sealed trait Position

  case object Start extends Position

  case object End extends Position

  final case class Index(value: AcsSnapshotStore.QueryAcsSnapshotPaginationToken) extends Position
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
      after: Option[AcsSnapshotStore.QueryAcsSnapshotPaginationToken],
  ): Future[(Position, Vector[SpliceCreatedEvent])] = {
    for {
      snapshot <- acsSnapshotStore.queryAcsSnapshot(
        timestamp.migrationId,
        snapshot = timestamp.timestamp,
        after,
        PageLimit.tryCreate(appConfig.dbReadChunkSize),
        Seq.empty,
        Seq.empty,
      )
    } yield (
      snapshot.afterToken.fold(End: Position)(Index(_)),
      snapshot.createdEventsInPage,
    )

  }

  private def encodeEvent(
      event: SpliceCreatedEvent,
      encoding: ScanStorageConfig.Encoding,
  ): String = {
    ScanHttpEncodings
      .fromDamlValueEncoding(encoding.damlValueEncoding)
      .javaToHttpActiveContract(event.eventId, event.recordTime, event.event)
      .asJson
      .noSpacesSortKeys
  }

  private def getSource: Source[Seq[String], NotUsed] = {
    Source
      .unfoldAsync(Start: Position) {
        case Start => getAcsSnapshotChunk(timestamp, None).map(Some(_))
        case Index(i) => getAcsSnapshotChunk(timestamp, Some(i)).map(Some(_))
        case End => Future.successful(None)
      }
      .map(events => {
        historyMetrics.BulkStorage.incContractsCount(events.length)
        events
      })
      .mapConcat(identity)
      .via(
        MultiEncodingBulkStorageFlow(
          encodeEvent,
          encoding =>
            S3ZstdObjects(
              storageConfig,
              appConfig,
              s3Connection,
              objIdx =>
                s"${storageConfig.getSegmentFolder(timestamp.timestamp, None)}/${encoding
                    .storageKey("ACS", objIdx)}",
              loggerFactory,
            ),
          encoding => historyMetrics.BulkStorage.incAcsSnapshotObjects(encoding.key, "staging"),
        )
      )
      .fold(Seq.empty[String])(_ :+ _)
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
  ): Flow[TimestampWithMigrationId, (TimestampWithMigrationId, Seq[String]), NotUsed] =
    Flow[TimestampWithMigrationId].flatMapConcat { ts =>
      new SingleAcsSnapshotBulkStorage(
        ts,
        storageConfig,
        appConfig,
        acsSnapshotStore,
        s3Connection,
        historyMetrics,
        loggerFactory,
      ).getSource.map(keys => (ts, keys))
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
  ): Source[Seq[String], NotUsed] =
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
