// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, RestartSource, Source}
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.{KillSwitches, RestartSettings, UniqueKillSwitch}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class AcsSnapshotBulkStorage(
    val config: ScanStorageConfig,
    val acsSnapshotStore: AcsSnapshotStore,
    val s3Connection: S3BucketConnection,
    val kvProvider: ScanKeyValueProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  // TODO(#3429): persist progress (or conclude it from the S3 storage), and start from latest successfully dumped snapshot upon restart
  private def getStartTimestamp: Future[Option[(Long, CantonTimestamp)]] =
    kvProvider.getLatestAcsSnapshotInBulkStorage().value

  // When new snapshot is not yet available, how long to wait for a new one.
  // TODO(#3429): make it longer for prod (so consider making it configurable/overridable for tests)
  private val snapshotPollingInterval = 5.seconds

  private def getAcsSnapshotTimestampsAfter(
      startMigrationId: Long,
      startTimestamp: CantonTimestamp,
  ): Source[(Long, CantonTimestamp), NotUsed] = {
    Source
      .unfoldAsync((startMigrationId, startTimestamp)) {
        case (lastMigrationId: Long, lastTimestamp: CantonTimestamp) =>
          acsSnapshotStore.lookupSnapshotAfter(lastMigrationId, lastTimestamp).flatMap {
            case Some(snapshot) =>
              logger.info(
                s"next snapshot available, at migration ${snapshot.migrationId}, record time ${snapshot.snapshotRecordTime}"
              )
              Future.successful(
                Some(
                  (
                    (snapshot.migrationId, snapshot.snapshotRecordTime),
                    Some((snapshot.migrationId, snapshot.snapshotRecordTime)),
                  )
                )
              )
            case None =>
              after(snapshotPollingInterval, actorSystem.scheduler) {
                logger.debug("No new snapshot available, sleeping...")
                Future.successful(Some(((lastMigrationId, lastTimestamp), None)))
              }
          }
      }
      .collect { case Some((migrationId, timestamp)) => (migrationId, timestamp) }
  }

  /**  This is the main implementation of the pipeline. It is a Pekko Source that reads a `start` timestamp
    *   from the DB, and starts dumping to S3 all snapshots (strictly) after `start`. After every snapshot that
    *   is successfully dumped, it persists to the DB its timestamp, and emits that timestamp as an output.
    *   It is an infinite source that should never complete.
    */
  private def mksrc() = {
    Source
      .future(getStartTimestamp)
      .flatMapConcat {
        case Some((startMigrationId, startAfterTimestamp)) =>
          logger.info(
            s"Latest dumped snapshot was from migration $startMigrationId, timestamp $startAfterTimestamp"
          )
          getAcsSnapshotTimestampsAfter(startMigrationId, startAfterTimestamp)
        case None =>
          logger.info("Not dumped snapshots yet, starting from genesis")
          getAcsSnapshotTimestampsAfter(0, CantonTimestamp.MinValue)
      }
      .via(
        SingleAcsSnapshotBulkStorage.asFlow(
          config,
          acsSnapshotStore,
          s3Connection,
          loggerFactory,
        )
      )
      .mapAsync(1) { case (migrationId, timestamp) =>
        for {
          _ <- kvProvider.setLatestAcsSnapshotsInBulkStorage(migrationId, timestamp)
        } yield {
          logger.info(
            s"Successfully completed dumping snapshots from migration $migrationId, timestamp $timestamp"
          )
          (migrationId, timestamp)
        }
      }
  }

  /**  wraps mksrc (where the main pipeline logic is implemented) in a retry loop, to retry upon failures.
    */
  def getSource(): Source[(Long, CantonTimestamp), UniqueKillSwitch] = {
    val restartSettings = RestartSettings(
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.1,
    )

    RestartSource
      .withBackoff(restartSettings)(() => mksrc())
      .viaMat(KillSwitches.single)(Keep.right)

  }
}
