// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.lfdecentralizedtrust.splice.store.KeyValueStore
import cats.data.OptionT
import cats.implicits.toBifunctorOps
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider.*

import scala.concurrent.{ExecutionContext, Future}

class ScanKeyValueProvider(val store: KeyValueStore, val loggerFactory: NamedLoggerFactory)
    extends NamedLogging {

  private val latestAcsSnapshotInBulkStorageKey = "latest_acs_snapshot_in_bulk_storage"

  final def setLatestAcsSnapshotsInBulkStorage(
      timestamp: CantonTimestamp,
      migrationId: Long,
  )(implicit tc: TraceContext): Future[Unit] = store.setValue(
    latestAcsSnapshotInBulkStorageKey,
    AcsSnapshotTimestampMigration(timestamp, migrationId),
  )

  final def getLatestAcsSnapshotInBulkStorage()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, (CantonTimestamp, Long)] = {
    val result: OptionT[Future, AcsSnapshotTimestampMigration] = store.readValueAndLogOnDecodingFailure(latestAcsSnapshotInBulkStorageKey)
    result.map(result => (result.timestamp, result.migrationId))
  }
}

object ScanKeyValueProvider {
  final case class AcsSnapshotTimestampMigration(
      timestamp: CantonTimestamp,
      migrationId: Long,
  )
  implicit val timestampCodec: Codec[CantonTimestamp] =
    Codec
      .from[Long](implicitly, implicitly)
      .iemap(timestamp => CantonTimestamp.fromProtoPrimitive(timestamp).leftMap(_.message))(
        _.toProtoPrimitive
      )
  implicit val acsSnapshotTimestampMigrationCodec: Codec[AcsSnapshotTimestampMigration] =
    deriveCodec[AcsSnapshotTimestampMigration]
}
