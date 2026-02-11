// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.config

import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.AcsSnapshot

import java.time.{Duration, ZoneOffset}
import java.time.temporal.ChronoField

/** Note that these configurations must be kept consistent between SVs,
  *  so they are not configured via a local config file in Scan. Instead, they must be voted on.
  *  For now, we support only a single hard-coded config in prod (scanStorageConfigV1 below),
  *  so we have not yet implemented voting on changing this.
  */

case class ScanStorageConfig(
    dbAcsSnapshotPeriodHours: Int, // Period between two consecutive acs snapshots to be computed and stored in the DB
    bulkAcsSnapshotPeriodHours: Int, // Period between two consecutive acs snapshots to be dumped to bulk storage (currently must be <=24 hr, and a multiple of dbAcsSnapshotPeriodHours)
    bulkDbReadChunkSize: Int, // Chunk size to read from the DB for copying to bulk storage
    bulkMaxFileSize: Long, // Max file size (estimated, may end up being slightly bigger) for bulk storage objects
) {
  require(
    dbAcsSnapshotPeriodHours > 0 && 24 % dbAcsSnapshotPeriodHours == 0,
    s"dbAcsSnapshotPeriodHours must be a factor of 24 (received: $dbAcsSnapshotPeriodHours)",
  )

  require(
    bulkAcsSnapshotPeriodHours >= dbAcsSnapshotPeriodHours &&
      24 % dbAcsSnapshotPeriodHours == 0 &&
      bulkAcsSnapshotPeriodHours % dbAcsSnapshotPeriodHours == 0,
    s"bulkAcsSnapshotPeriodHours must be a factor of 24 and of dbAcsSnapshotPeriodHours (received: $bulkAcsSnapshotPeriodHours)",
  )

  private def timesToDoSnapshot(periodHours: Int) = (0 to 23).filter(_ % periodHours == 0)

  // Simplified version of computeSnapshotTimeAfter, which is correct only if `lastSnapshot` is a "legal" snapshot timestamp
  // Since we get an AcsSnapshot here and not an arbitrary CantonTimestamp, we can assume that this snapshot is valid.
  def nextSnapshotTime(lastSnapshot: AcsSnapshot): CantonTimestamp = {
    lastSnapshot.snapshotRecordTime.plus(Duration.ofHours(dbAcsSnapshotPeriodHours.toLong))
  }

  def computeSnapshotTimeAfter(
      afterRecordTime: CantonTimestamp,
      periodHours: Int,
  ): CantonTimestamp = {
    val afterTimeUTC = afterRecordTime.toInstant.atOffset(ZoneOffset.UTC)
    val (hourForSnapshot, plusDays) = timesToDoSnapshot(periodHours)
      .find(_ > afterTimeUTC.get(ChronoField.HOUR_OF_DAY)) match {
      case Some(hour) => hour -> 0 // current day at hour
      case None => 0 -> 1 // next day at 00:00
    }
    val until = afterTimeUTC.toLocalDate
      .plusDays(plusDays.toLong)
      .atTime(hourForSnapshot, 0)
      .toInstant(ZoneOffset.UTC)
    CantonTimestamp.assertFromInstant(until)
  }

  def computeBulkSnapshotTimeAfter(afterRecordTime: CantonTimestamp): CantonTimestamp =
    computeSnapshotTimeAfter(afterRecordTime, bulkAcsSnapshotPeriodHours)

  def computeDbSnapshotTimeAfter(afterRecordTime: CantonTimestamp): CantonTimestamp =
    computeSnapshotTimeAfter(afterRecordTime, dbAcsSnapshotPeriodHours)

  def shouldDumpSnapshotToBulkStorage(snapshotTimestamp: CantonTimestamp): Boolean =
    timesToDoSnapshot(bulkAcsSnapshotPeriodHours).contains(
      snapshotTimestamp.toInstant.atOffset(ZoneOffset.UTC).get(ChronoField.HOUR_OF_DAY)
    )

}

object ScanStorageConfigs {
  val scanStorageConfigV1 = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 3,
    bulkAcsSnapshotPeriodHours = 24,
    bulkDbReadChunkSize = 1000,
    bulkMaxFileSize = 64L * 1024 * 1024,
  )
}
