// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.pattern.after
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class AcsSnapshotBulkStorage(
    val config: BulkStorageConfig,
    val acsSnapshotStore: AcsSnapshotStore,
    val s3Connection: S3BucketConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  // TODO(#3429): persist progress, and start from latest successfully dumped snapshot upon restart
  private def getStartTimestamp: (Long, CantonTimestamp) = (0, CantonTimestamp.MinValue)

  private def timestampsSource: Source[(Long, CantonTimestamp), NotUsed] = {
    Source
      .unfoldAsync(getStartTimestamp) {
        case (lastMigrationId: Long, lastTimestamp: CantonTimestamp) =>
          acsSnapshotStore.lookupSnapshotAfter(lastMigrationId, lastTimestamp).flatMap {
            case Some(snapshot) =>
              logger.debug(
                s"next snapshot available, at migration ${snapshot.migrationId}, record time ${snapshot.snapshotRecordTime}"
              )
              Future.successful(
                Some(
                  (snapshot.migrationId, snapshot.snapshotRecordTime),
                  Some(snapshot.migrationId, snapshot.snapshotRecordTime),
                )
              )
            case None =>
              after(5.seconds, actorSystem.scheduler) {
                Future.successful(Some((lastMigrationId, lastTimestamp), None))
              }
          }
      }
      .collect { case Some((migrationId, timestamp)) => (migrationId, timestamp) }
  }

  def getSource: Source[Unit, NotUsed] = {
    timestampsSource
      .via(SingleAcsSnapshotBulkStorage(config, acsSnapshotStore, s3Connection, loggerFactory))
      .map(_ => ())
  }
}
