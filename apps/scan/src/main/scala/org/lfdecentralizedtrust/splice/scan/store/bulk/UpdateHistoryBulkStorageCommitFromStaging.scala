// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.store.{S3BucketConnection, TimestampWithMigrationId}

import scala.concurrent.{ExecutionContextExecutor, Future}

class UpdateHistoryBulkStorageCommitFromStaging(
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    bulkStorageReader: BulkStorageReader,
    appConfig: BulkStorageConfig,
    scanConnection: PeerBftScanConnection,
    onObjectCommitted: Seq[S3BucketConnection.ObjectKeyAndChecksum] => Unit,
    val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends UpdateHistoryBulkStorageWriter
    with NamedLogging {
  override def processSegmentsFlow(implicit
      tc: TraceContext
  ): Flow[UpdatesSegment, UpdatesSegment, NotUsed] =
    BulkStorageCommitFromStaging(
      stagingS3Connection,
      committedS3Connection,
      segment =>
        bulkStorageReader
          .getStagingObjectsForUpdateHistorySegment(segment)
          .map(objects => objects.objects)
          .recover {
            // If we restart after all objects have been moved already, the staging objects will not be found.
            case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
              Seq.empty
          },
      _.toTimestamp.timestamp,
      appConfig,
      scanConnection,
      loggerFactory,
      onObjectCommitted,
    )

  override def getNextSegmentAfter(
      after: Option[UpdatesSegment]
  )(implicit tc: TraceContext): Future[Option[UpdatesSegment]] = {
    bulkStorageReader
      .getStagingSegmentStartingAt(
        after.map(_.toTimestamp.timestamp)
      )
      .map(
        _.map(segment =>
          // We don't care about migration IDs in commit-from-staging pipelines
          UpdatesSegment(
            TimestampWithMigrationId(segment._1, -1L),
            TimestampWithMigrationId(segment._2, -1L),
          )
        )
      )
  }
}
