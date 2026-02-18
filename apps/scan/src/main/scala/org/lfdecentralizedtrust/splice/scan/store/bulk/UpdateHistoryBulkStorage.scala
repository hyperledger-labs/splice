// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.pattern.after
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{KillSwitches, RestartSettings, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.{Keep, RestartSource, Source}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.store.{HardLimit, TimestampWithMigrationId, UpdateHistory}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class UpdateHistoryBulkStorage(
    val config: ScanStorageConfig,
    val updateHistory: UpdateHistory,
    val kvProvider: ScanKeyValueProvider,
    val currentMigrationId: Long,
    val s3Connection: S3BucketConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  private def getMigrationIdForAcsSnapshot(snapshotTimestamp: CantonTimestamp): Future[Long] = {
    /* The migration ID in ACS snapshots is always the lowest migration that has updates with a later record time,
       because we only create an ACS snapshot in an app if it has seen updates with a later timestamp.
       If no such updates exist, then we assume that the current migration will be that of the snapshot. If a migration
       happens before that time, then the app will restart with a higher migration, and therefore also restart dumping
       this segment.
     */
    updateHistory
      .getLowestMigrationForRecordTime(snapshotTimestamp)
      .map(_.getOrElse(currentMigrationId))
  }

  private def getSegmentEndAfter(ts: TimestampWithMigrationId): Future[TimestampWithMigrationId] = {
    val endTs = config.computeBulkSnapshotTimeAfter(ts.timestamp)
    for {
      endMigration <-
        if (ts.migrationId < currentMigrationId) {
          getMigrationIdForAcsSnapshot(endTs)
        } else {
          /* When dumping updates for the current migration ID, we always assume that this migration ID
           continues beyond the segment, i.e. that the current migration ID is also the migration ID at
           the end of the segment. If this does not hold, and a migration happens before the end of the
           segment, then:
           a. this app will stop ingesting updates before the end of the segment, hence this segment will not be considered completed
           b. eventually, the app will be restarted with the new migration ID, and this segment will be retried in the new app,
              where (ts.migrationId == currentMigrationId) will no longer hold.
           */
          Future.successful(currentMigrationId)
        }
    } yield {
      TimestampWithMigrationId(endTs, endMigration)
    }
  }

  /** Gets the very first updates segment for this network after genesis
    * May return None if unknown yet. This could happen if no updates have been ingested,
    * so we do not know the genesis record time yet. The caller should then schedule a retry.
    */
  private def getFirstSegmentFromGenesis: Future[Option[UpdatesSegment]] =
    for {
      firstUpdate <- updateHistory.getUpdatesWithoutImportUpdates(None, HardLimit.tryCreate(1))
      segmentEnd <- firstUpdate.headOption match {
        case None => Future.successful(None)
        case Some(first) =>
          getSegmentEndAfter(
            TimestampWithMigrationId(first.update.update.recordTime, first.migrationId)
          ).map(Some(_))
      }
    } yield {
      segmentEnd.map(UpdatesSegment(TimestampWithMigrationId(CantonTimestamp.MinValue, 0), _))
    }

  /** Gets the segment from which this app should start dumping, e.g. after a restart.
    * May return None if unknown yet. The caller should then sleep and retry.
    */
  private def getFirstSegment: Future[Option[UpdatesSegment]] =
    kvProvider.getLatestUpdatesSegmentInBulkStorage().value.flatMap {
      case None => getFirstSegmentFromGenesis
      case Some(after) => getNextSegment(Some(after))
    }

  private def getNextSegment(afterO: Option[UpdatesSegment]): Future[Option[UpdatesSegment]] =
    afterO match {
      case Some(previous) =>
        getSegmentEndAfter(previous.toTimestamp).map(end =>
          Some(UpdatesSegment(previous.toTimestamp, end))
        )
      case None => getFirstSegment
    }

  private def mksrc()(implicit
      ec: ExecutionContext,
      actorSystem: org.apache.pekko.actor.ActorSystem,
  ) = {
    Source
      .unfoldAsync(Option.empty[UpdatesSegment]) { current =>
        getNextSegment(current).flatMap {
          case Some(next) =>
            logger.info(s"Dumping next updates segment: $next")
            Future.successful(Some((Some(next), Some(next))))
          case None =>
            logger.debug(s"Next segment after $current not known yet, scheduling next attempt...")
            after(5.seconds, actorSystem.scheduler)(
              Future.successful(Some((current, None)))
            )
        }
      }
      .collect { case Some(segment) => segment }
      .via(
        UpdateHistorySegmentBulkStorage.asFlow(
          config,
          updateHistory,
          s3Connection,
          loggerFactory,
        )
      )
      .mapAsync(1) { case (segment, lastUpdate) =>
        lastUpdate match {
          case Some(ts) =>
            logger.info(
              s"Successfully completed dumping updates. Last update from the segment is from ${ts.migrationId}, ${ts.timestamp}"
            )
          case None =>
            // This might happen e.g. due to a long migration, but we at least want to warn about it
            logger.warn(s"Segment $segment contained no updates")
        }
        kvProvider.setLatestUpdatesSegmentInBulkStorage(segment).map(_ => segment)
      }
  }

  def getSource(): Source[UpdatesSegment, UniqueKillSwitch] = {
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
