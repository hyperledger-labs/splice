// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.pattern.after
import org.lfdecentralizedtrust.splice.{PekkoRetryingService, PekkoRetryableService}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

trait AcsSnapshotBulkStorageWriter[Snapshot] {

  def positionAt(recordTime: CantonTimestamp)(implicit tc: TraceContext): Future[Snapshot]

  def recordTimeOf(snapshot: Snapshot): CantonTimestamp

  /** This method should return the next snapshot available, after `last`, if any.
    * The pipeline will poll this method until it returns a new snapshot, and then process that snapshot
    * before polling for the next one. It is ok for this method to return a snapshot that should not actually
    * be processed, as the pipeline will call `shouldProcessSnapshot` to check if the snapshot should be
    * processed or skipped (but will remember that it was skipped and update `last` for the future calls accordingly).
    */
  def getNextSnapshotAfter(
      last: Snapshot
  )(implicit tc: TraceContext): Future[Option[Snapshot]]

  /** This method should return true if the given snapshot should be processed,
    * or false if it should be skipped. This is used to skip snapshots that are not relevant for bulk storage
    * (e.g. because they are in the DB, but are more frequent than the frequency we need for bulk storage).
    */
  def shouldProcessSnapshot(snapshot: Snapshot)(implicit
      tc: TraceContext
  ): Boolean

  /** Return the main Flow that processes snapshots.
    * The Flow must emit back the same snapshots as its output on every processed snapshot.
    */
  def processSnapshotsFlow(implicit
      tc: TraceContext,
      actorSystem: ActorSystem,
  ): Flow[Snapshot, Snapshot, NotUsed]

}

class AcsSnapshotBulkStoragePersistentProgress(
    latestSnapshotKvStoreKey: String,
    firstSnapshotKvStoreKey: String,
    kvProvider: ScanKeyValueProvider,
    metric: MetricHandle.Gauge[CantonTimestamp],
    override val loggerFactory: NamedLoggerFactory,
) extends NamedLogging
    with Spanning {

  import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider.timestampCodec

  def readLatestProcessedSnapshotTimestamp(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[CantonTimestamp]] = {
    kvProvider.store.readValueAndLogOnDecodingFailure(latestSnapshotKvStoreKey).value
  }

  def readFirstSnapshotTimestamp(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[CantonTimestamp]] = {
    kvProvider.store.readValueAndLogOnDecodingFailure(firstSnapshotKvStoreKey).value
  }

  def reset(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = {
    for {
      _ <- kvProvider.store.deleteKey(latestSnapshotKvStoreKey)
      _ <- kvProvider.store.deleteKey(firstSnapshotKvStoreKey)
    } yield {}
  }

  def persistLatestProcessedSnapshotTimestamp(ts: CantonTimestamp)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = {

    metric.updateValue(ts)
    for {
      _ <- kvProvider.store
        .setValueIfNotExists(firstSnapshotKvStoreKey, ts)
      _ <- kvProvider.store
        .setValue(latestSnapshotKvStoreKey, ts)
        .map(_ => {
          logger.info(
            s"Successfully completed processing snapshots up to timestamp $ts"
          )
        })
    } yield {}
  }
}

class AcsSnapshotBulkStorage[Snapshot](
    description: String,
    writer: AcsSnapshotBulkStorageWriter[Snapshot],
    val persistentProgress: AcsSnapshotBulkStoragePersistentProgress,
    appConfig: BulkStorageConfig,
    mayStart: Source[Boolean, Cancellable],
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning
    with PekkoRetryableService[CantonTimestamp] {

  private def getAcsSnapshotsAfter(
      start: Snapshot
  )(implicit tc: TraceContext): Source[Snapshot, NotUsed] = {
    Source
      .unfoldAsync(start) { (last: Snapshot) =>
        writer.getNextSnapshotAfter(last).flatMap {
          case Some(snapshot) =>
            logger.info(s"next snapshot available: $snapshot")
            Future.successful(
              Some(
                (
                  snapshot,
                  Some(snapshot),
                )
              )
            )
          case None =>
            logger.debug("No new snapshot available, sleeping...")
            after(
              appConfig.snapshotPollingInterval.underlying,
              actorSystem.scheduler,
            ) {
              Future.successful(Some((last, None)))
            }
        }
      }
      .collect { case Some(snapshot) => snapshot }
  }

  /**  This is the main implementation of the pipeline. It is a Pekko Source that reads a `start` timestamp
    *   from the DB, and starts dumping to S3 all snapshots (strictly) after `start`. After every snapshot that
    *   is successfully dumped, it persists to the DB its timestamp, and emits that timestamp as an output.
    *   It is an infinite source that should never complete.
    */
  private def mksrc()(implicit tc: TraceContext): Source[CantonTimestamp, Cancellable] = {

    mayStart.flatMap { _ =>
      Source
        .future(persistentProgress.readLatestProcessedSnapshotTimestamp.flatMap {
          case Some(start) =>
            logger.info(s"Latest processed snapshot was at timestamp $start")
            writer.positionAt(start)
          case None =>
            logger.info("No processed snapshots yet, starting from genesis")
            writer.positionAt(CantonTimestamp.MinValue)
        })
        .flatMapConcat(getAcsSnapshotsAfter)
        .filter { writer.shouldProcessSnapshot }
        .via(writer.processSnapshotsFlow)
        .mapAsync(1) { snapshot =>
          val ts = writer.recordTimeOf(snapshot)
          persistentProgress.persistLatestProcessedSnapshotTimestamp(ts).map(_ => ts)
        }
    }
  }

  override def asPekkoRetryingService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[CantonTimestamp] = {
    withNewTrace(description) { implicit traceContext => _ =>
      val src = mksrc()
      new PekkoRetryingService(
        src,
        Sink.ignore,
        automationConfig,
        backoffClock,
        description,
        retryProvider,
        loggerFactory,
      )
    }
  }
}

object AcsSnapshotBulkStorage {
  case class AcsSnapshotObjects(
      timestamp: CantonTimestamp,
      objects: Seq[ObjectKeyAndChecksum],
  )
}
