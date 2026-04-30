// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.config

import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  IncrementalAcsSnapshot,
}

import java.time.{Duration, Instant, ZoneOffset}
import java.time.temporal.{ChronoField, ChronoUnit}

/** Note that these configurations must be kept consistent between SVs,
  *  so they are not configured via a local config file in Scan. Instead, they must be voted on.
  *  For now, we support only a single hard-coded config in prod (scanStorageConfigV1 below),
  *  so we have not yet implemented voting on changing this.
  */

case class ScanStorageConfig(
    dbAcsSnapshotPeriodHours: Int, // Period between two consecutive acs snapshots to be computed and stored in the DB
    bulkAcsSnapshotPeriodHours: Int, // Period between two consecutive acs snapshots to be dumped to bulk storage (currently must be <=24 hr, and a multiple of dbAcsSnapshotPeriodHours)
    bulkDbReadChunkSize: Int, // Chunk size to read from the DB for copying to bulk storage
    bulkZstdFrameSize: Long, // Size of each zstd frame. In prod, must be >= 5 MB as each frame is written as a part in multi-part upload, which are enforced by most s3 implementations to be >= 5MB each
    bulkMaxFileSize: Long, // Max file size (estimated, may end up being slightly bigger) for bulk storage objects
    zstdCompressionLevel: Int,
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
  def nextSnapshotTime(lastSnapshot: IncrementalAcsSnapshot): CantonTimestamp = {
    lastSnapshot.targetRecordTime.plus(Duration.ofHours(dbAcsSnapshotPeriodHours.toLong))
  }

  def atHour(time: CantonTimestamp, plusDays: Int, atHour: Int): CantonTimestamp =
    CantonTimestamp.assertFromInstant(
      time.toInstant
        .plus(plusDays.toLong, ChronoUnit.DAYS)
        .atOffset(ZoneOffset.UTC)
        .withHour(atHour)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
        .toInstant
    )

  def computeSnapshotTimeAfter(
      afterRecordTime: CantonTimestamp,
      periodHours: Int,
  ): CantonTimestamp = {
    val (hourForSnapshot, plusDays) = timesToDoSnapshot(periodHours)
      .find(_ > afterRecordTime.toInstant.atZone(ZoneOffset.UTC).getHour) match {
      case Some(hour) => hour -> 0 // current day at hour
      case None => 0 -> 1 // next day at 00:00
    }
    atHour(afterRecordTime, plusDays, hourForSnapshot)
  }

  def computeSnapshotTimeAtOrBefore(
      upToRecordTime: CantonTimestamp,
      periodHours: Int,
  ): CantonTimestamp = {
    val rtHours = upToRecordTime.toInstant.atZone(ZoneOffset.UTC).getHour
    val hourForSnapshot = timesToDoSnapshot(periodHours)
      .find(_ <= rtHours)
      .getOrElse(
        throw new RuntimeException(
          s"Unexpectedly could not find a time for a snapshot before ${rtHours}"
        )
      )
    atHour(upToRecordTime, 0, hourForSnapshot)
  }

  def computeBulkSnapshotTimeAfter(afterRecordTime: CantonTimestamp): CantonTimestamp =
    computeSnapshotTimeAfter(afterRecordTime, bulkAcsSnapshotPeriodHours)

  def computeBulkSnapshotTimeAtOrBefore(upToRecordTime: CantonTimestamp): CantonTimestamp =
    computeSnapshotTimeAtOrBefore(upToRecordTime, bulkAcsSnapshotPeriodHours)

  def computeDbSnapshotTimeAfter(afterRecordTime: CantonTimestamp): CantonTimestamp =
    computeSnapshotTimeAfter(afterRecordTime, dbAcsSnapshotPeriodHours)

  def shouldDumpSnapshotToBulkStorage(snapshotTimestamp: CantonTimestamp): Boolean =
    timesToDoSnapshot(bulkAcsSnapshotPeriodHours).contains(
      snapshotTimestamp.toInstant.atOffset(ZoneOffset.UTC).get(ChronoField.HOUR_OF_DAY)
    )

  def getSegmentFolder(
      segmentStartTimestamp: CantonTimestamp,
      segmentEndTimestamp: Option[CantonTimestamp],
  ): String = {
    val endTimestamp = segmentEndTimestamp.getOrElse(
      computeBulkSnapshotTimeAfter(segmentStartTimestamp)
    )
    s"$segmentStartTimestamp~$endTimestamp"
  }

  def getStartAndEndTimestampsForFolder(
      folder: String
  ): Either[String, (CantonTimestamp, CantonTimestamp)] = {
    folder.stripSuffix("/").split("~") match {
      case Array(folderStartStr, folderEndStr) =>
        for {
          folderStart <- CantonTimestamp.fromInstant(Instant.parse(folderStartStr))
          folderEnd <- CantonTimestamp.fromInstant(Instant.parse(folderEndStr))
        } yield {
          (folderStart, folderEnd)
        }
      case _ =>
        Left(
          s"Cannot parse folder name: $folder (wrong format, expected 'startTimestamp~endTimestamp')"
        )
    }
  }

  def findSegmentFolderPrefixByStartTimestamp(
      segmentStartTimestamp: CantonTimestamp
  ): String = segmentStartTimestamp.toString

}

object ScanStorageConfigs {
  val scanStorageConfigV1 = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 3,
    bulkAcsSnapshotPeriodHours = 24,
    bulkDbReadChunkSize = 1000,
    bulkZstdFrameSize = 12L * 1024 * 1024,
    bulkMaxFileSize = 128L * 1024 * 1024,
    zstdCompressionLevel = 3,
  )
}
