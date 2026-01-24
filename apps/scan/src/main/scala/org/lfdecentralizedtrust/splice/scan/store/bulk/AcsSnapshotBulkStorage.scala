// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.PekkoUtil.RetrySourcePolicy
import com.digitalasset.canton.util.{ErrorUtil, PekkoUtil}
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Source}
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.{KillSwitch, KillSwitches}
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class AcsSnapshotBulkStorage(
    val config: BulkStorageConfig,
    val acsSnapshotStore: AcsSnapshotStore,
    val s3Connection: S3BucketConnection,
    val kvProvider: ScanKeyValueProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  // TODO(#3429): persist progress (or conclude it from the S3 storage), and start from latest successfully dumped snapshot upon restart
  private def getStartTimestamp: Future[Option[(Long, CantonTimestamp)]] = kvProvider.getLatestAcsSnapshotInBulkStorage().value

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

  /** This is the main implementation of the pipeline. It is a Pekko Source that gets a `start` timestamp, and starts dumping to S3
    *  all snapshots (strictly) after `start`. It is an infinite source that should never complete.
    */
  private def mksrc(): Source[(Long, CantonTimestamp), (KillSwitch, Future[Done])] =  {
      logger.debug("Starting ACS snapshot dump source")
      val base =
        Source.single[Unit](())
          .mapAsync(1){_ => getStartTimestamp}
          .flatMapConcat {
            case Some((startMigrationId, startAfterTimestamp)) => getAcsSnapshotTimestampsAfter(startMigrationId, startAfterTimestamp)
            case None => getAcsSnapshotTimestampsAfter(0, CantonTimestamp.MinValue)
          }
          .via(
            SingleAcsSnapshotBulkStorage.asFlow(
              config,
              acsSnapshotStore,
              s3Connection,
              loggerFactory,
            )
          )
          .mapAsync(1){case (migrationId, timestamp) =>
            for {
              _ <- kvProvider.setLatestAcsSnapshotsInBulkStorage(migrationId, timestamp)
            } yield {
              (migrationId, timestamp)
            }
          }


    val withKs = base.viaMat(KillSwitches.single)(Keep.right)
      withKs.watchTermination() { case (ks, done) => (ks: KillSwitch, done) }
  }

  /**  wraps mksrc (where the main pipeline logic is implemented) in a retry loop, to retry upon failures.
    */
  def getSource
      : Source[PekkoUtil.WithKillSwitch[(Long, CantonTimestamp)], (KillSwitch, Future[Done])] = {
    // TODO(#3429): once we persist the state, i.e. the last dumped snapshot, consider moving from Canton's PekkoUtil.restartSource
    //  to Pekko's built-in RestartSource (for now, it's convenient to use Canton's ability to track state via lastEmittedElement)
    // TODO(#3429): tweak the retry parameters here
    val delay = FiniteDuration(5, "seconds")
    val policy = new RetrySourcePolicy[Unit, (Long, CantonTimestamp)] {
      override def shouldRetry(
          lastState: Unit,
          lastEmittedElement: Option[(Long, CantonTimestamp)],
          lastFailure: Option[Throwable],
      ): Option[(scala.concurrent.duration.FiniteDuration, Unit)] = {
        lastFailure.map { t =>
          logger.warn(
            s"Writing ACS snapshot to bulk storage failed with : ${ErrorUtil
                .messageWithStacktrace(t)}, will retry after delay of $delay from last successful timestamp $lastEmittedElement"
          )
          // Always retry (TODO(#3429): consider a max number of retries?)
          delay -> ()
        }
      }
    }

    PekkoUtil
      .restartSource(
        name = "acs-snapshot-dump",
        initial = (),
        mkSource = (_: Unit) => mksrc(),
        policy = policy,
      )

  }

}
