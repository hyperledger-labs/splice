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
import org.lfdecentralizedtrust.splice.store.TimestampWithMigrationId

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

  private def getStartTimestamp: Future[Option[TimestampWithMigrationId]] =
    kvProvider.getLatestAcsSnapshotInBulkStorage().value

  // When new snapshot is not yet available, how long to wait for a new one.
  // TODO(#3429): make it longer for prod (so consider making it configurable/overridable for tests)
  private val snapshotPollingInterval = 5.seconds

  private def getAcsSnapshotTimestampsAfter(
      start: TimestampWithMigrationId
  ): Source[TimestampWithMigrationId, NotUsed] = {
    Source
      .unfoldAsync(start) { (last: TimestampWithMigrationId) =>
        acsSnapshotStore.lookupSnapshotAfter(last.migrationId, last.timestamp).flatMap {
          case Some(snapshot) =>
            logger.info(
              s"next snapshot available, at migration ${snapshot.migrationId}, record time ${snapshot.snapshotRecordTime}"
            )
            Future.successful(
              Some(
                (
                  TimestampWithMigrationId(snapshot.snapshotRecordTime, snapshot.migrationId),
                  Some(TimestampWithMigrationId(snapshot.snapshotRecordTime, snapshot.migrationId)),
                )
              )
            )
          case None =>
            logger.debug("No new snapshot available, sleeping...")
            after(snapshotPollingInterval, actorSystem.scheduler) {
              Future.successful(Some((last, None)))
            }
        }
      }
      .collect { case Some(ts) => ts }
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
        case Some(start: TimestampWithMigrationId) =>
          logger.info(
            s"Latest dumped snapshot was from migration ${start.migrationId}, timestamp ${start.timestamp}"
          )
          getAcsSnapshotTimestampsAfter(start)
        case None =>
          logger.info("No dumped snapshots yet, starting from genesis")
          getAcsSnapshotTimestampsAfter(TimestampWithMigrationId(CantonTimestamp.MinValue, 0))
      }
      .filter { case TimestampWithMigrationId(ts, _) =>
        val ret = config.shouldDumpSnapshotToBulkStorage(ts)
        if (ret) {
          logger.debug(s"Dumping snapshot at timestamp $ts to bulk storage")
        } else {
          logger.info(
            s"Skipping snapshot at timestamp $ts for bulk storage, not required per the configured period of ${config.bulkAcsSnapshotPeriodHours}"
          )
        }
        ret
      }
      .via(
        SingleAcsSnapshotBulkStorage.asFlow(
          config,
          acsSnapshotStore,
          s3Connection,
          loggerFactory,
        )
      )
      .mapAsync(1) { (ts: TimestampWithMigrationId) =>
        for {
          _ <- kvProvider.setLatestAcsSnapshotsInBulkStorage(ts)
        } yield {
          logger.info(
            s"Successfully completed dumping snapshots from migration ${ts.migrationId}, timestamp ${ts.timestamp}"
          )
          ts
        }
      }
  }

  /**  wraps mksrc (where the main pipeline logic is implemented) in a retry loop, to retry upon failures.
    */
  def getSource(): Source[TimestampWithMigrationId, UniqueKillSwitch] = {
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
