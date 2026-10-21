// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.store.S3BucketConnection

import scala.concurrent.{ExecutionContextExecutor, Future}

class AcsSnapshotBulkStorageCommitFromStaging(
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    bulkStorageReader: BulkStorageReader,
    appConfig: BulkStorageConfig,
    scanConnection: PeerBftScanConnection,
    onObjectCommitted: Seq[S3BucketConnection.ObjectKeyAndChecksum] => Unit,
    val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends AcsSnapshotBulkStorageWriter[CantonTimestamp]
    with NamedLogging {

  override def positionAt(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[CantonTimestamp] = Future.successful(recordTime)

  override def recordTimeOf(snapshot: CantonTimestamp): CantonTimestamp = snapshot

  override def getNextSnapshotAfter(
      last: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[CantonTimestamp]] =
    bulkStorageReader.getTimestampOfStagingAcsSnapshotAfter(last)

  override def shouldProcessSnapshot(snapshot: CantonTimestamp)(implicit
      tc: TraceContext
  ): Boolean = true // all objects in staging should be moved to committed

  override def processSnapshotsFlow(implicit
      tc: TraceContext,
      actorSystem: ActorSystem,
  ): Flow[CantonTimestamp, CantonTimestamp, NotUsed] = {
    BulkStorageCommitFromStaging(
      stagingS3Connection,
      committedS3Connection,
      BulkObjectKind.Snapshots,
      ts =>
        bulkStorageReader
          .getStagingObjectsForAcsSnapshotAt(ts)
          .map(objects => objects.objects)
          .recover {
            // If we restart after all objects have been moved already, the staging objects will not be found.
            case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
              Seq.empty
          },
      identity,
      appConfig,
      scanConnection,
      loggerFactory,
      onObjectCommitted,
    )
  }
}
