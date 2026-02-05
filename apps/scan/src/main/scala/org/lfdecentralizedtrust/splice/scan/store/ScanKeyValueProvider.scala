// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.lfdecentralizedtrust.splice.store.{KeyValueStore, TimestampWithMigrationId}
import cats.data.OptionT
import cats.implicits.toBifunctorOps
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider.*
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdatesSegment

import scala.concurrent.{ExecutionContext, Future}

class ScanKeyValueProvider(val store: KeyValueStore, val loggerFactory: NamedLoggerFactory)
    extends NamedLogging {

  private val latestAcsSnapshotInBulkStorageKey = "latest_acs_snapshot_in_bulk_storage"
  private val latestUpdatesSegmentInBulkStorageKey = "latest_updates_segment_in_bulk_storage"

  final def setLatestAcsSnapshotsInBulkStorage(
      ts: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[Unit] = store.setValue(
    latestAcsSnapshotInBulkStorageKey,
    ts,
  )

  final def getLatestAcsSnapshotInBulkStorage()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, TimestampWithMigrationId] = {
    store.readValueAndLogOnDecodingFailure(latestAcsSnapshotInBulkStorageKey)
  }

  final def setLatestUpdatesSegmentInBulkStorage(
      segment: UpdatesSegment
  )(implicit tc: TraceContext): Future[Unit] = store.setValue(
    latestUpdatesSegmentInBulkStorageKey,
    segment,
  )

  final def getLatestUpdatesSegmentInBulkStorage()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, UpdatesSegment] =
    store.readValueAndLogOnDecodingFailure(latestUpdatesSegmentInBulkStorageKey)
}

object ScanKeyValueProvider {
  implicit val timestampCodec: Codec[CantonTimestamp] =
    Codec
      .from[Long](implicitly, implicitly)
      .iemap(timestamp => CantonTimestamp.fromProtoPrimitive(timestamp).leftMap(_.message))(
        _.toProtoPrimitive
      )
  implicit val acsSnapshotTimestampMigrationCodec: Codec[TimestampWithMigrationId] =
    deriveCodec[TimestampWithMigrationId]

  implicit val updatesSegmentCodec: Codec[UpdatesSegment] = deriveCodec[UpdatesSegment]
}
