// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.scan.store.bulk.{
  AcsSnapshotBulkStoragePersistentProgress,
  UpdateHistoryBulkStoragePersistentProgress,
  UpdatesSegment,
}
import org.lfdecentralizedtrust.splice.store.TimestampWithMigrationId

import scala.concurrent.{ExecutionContext, Future}

trait BackfillingProgress {
  def readUpdatesCursor(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[UpdatesSegment]]

  def persistUpdatesCursor(segment: UpdatesSegment)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit]

  def readSnapshotsCursor(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[TimestampWithMigrationId]]

  def persistSnapshotsCursor(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit]

  def isComplete(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean]

  def markComplete()(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit]

  def resetCompletion()(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit]
}

class KvBackfillingProgress(
    updatesStaging: UpdateHistoryBulkStoragePersistentProgress,
    snapshotsStaging: AcsSnapshotBulkStoragePersistentProgress,
    kvProvider: ScanKeyValueProvider,
    override val loggerFactory: NamedLoggerFactory,
) extends BackfillingProgress
    with NamedLogging {

  import KvBackfillingProgress.completeKvStoreKey

  override def readUpdatesCursor(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[UpdatesSegment]] = updatesStaging.readLatestProcessedSegment

  override def persistUpdatesCursor(segment: UpdatesSegment)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = updatesStaging.persistLatestProcessedSegment(segment)

  override def readSnapshotsCursor(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[TimestampWithMigrationId]] =
    snapshotsStaging.readLatestProcessedSnapshotTimestamp

  override def persistSnapshotsCursor(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = snapshotsStaging.persistLatestProcessedSnapshotTimestamp(ts)

  override def isComplete(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean] =
    kvProvider.store
      .readValueAndLogOnDecodingFailure[Boolean](completeKvStoreKey)
      .value
      .map(_.getOrElse(false))

  override def markComplete()(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    kvProvider.store
      .setValue(completeKvStoreKey, true)
      .map(_ => logger.info("Bulk storage backfilling from peers is complete"))

  override def resetCompletion()(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    kvProvider.store.deleteKey(completeKvStoreKey)
}

object KvBackfillingProgress {
  val completeKvStoreKey = "bulk_storage_backfill_complete"
}
