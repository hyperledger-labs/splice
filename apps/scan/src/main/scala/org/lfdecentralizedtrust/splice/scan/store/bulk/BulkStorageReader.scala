// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import cats.data.NonEmptyList
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.bulk.AcsSnapshotBulkStorage.AcsSnapshotObjects
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdateHistoryBulkStorage.UpdateHistoryObjectsResponse
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, PageLimit, S3BucketConnection}

import scala.concurrent.{ExecutionContext, Future}

class BulkStorageReader(
    val acsSnapshotStagingProgress: AcsSnapshotBulkStoragePersistentProgress,
    val acsSnapshotCommittedProgress: AcsSnapshotBulkStoragePersistentProgress,
    val updateHistoryStagingProgress: UpdateHistoryBulkStoragePersistentProgress,
    val updateHistoryCommittedProgress: UpdateHistoryBulkStoragePersistentProgress,
    storageConfig: ScanStorageConfig,
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    firstOwnSegmentStart: () => Future[Option[CantonTimestamp]],
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  def getCommittedObjectsForAcsSnapshotAtOrBefore(
      atOrBeforeTimestamp: CantonTimestamp,
      storageEncodings: NonEmptyList[ScanStorageConfig.Encoding],
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[AcsSnapshotObjects] = {
    for {
      snapshotTs <-
        acsSnapshotCommittedProgress.readLatestProcessedSnapshotTimestamp
          .map {
            case None =>
              throw Status.NOT_FOUND
                .withDescription("no snapshot in committed bulk storage yet")
                .asRuntimeException()
            case Some(ts) if ts.timestamp < atOrBeforeTimestamp =>
              logger.trace(
                s"Latest snapshot in committed bulk storage is at ${ts.timestamp}, which is before the requested timestamp $atOrBeforeTimestamp, returning that one"
              )
              ts.timestamp
            case Some(ts) => storageConfig.computeBulkSnapshotTimeAtOrBefore(atOrBeforeTimestamp)
          }
      objects <- getAcsSnapshotObjects(
        snapshotTs,
        committedS3Connection,
        storageConfig,
        storageEncodings,
      )
    } yield {
      objects
    }
  }

  def getTimestampOfStagingAcsSnapshotAfter(
      afterTimestamp: CantonTimestamp
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Option[CantonTimestamp]] = {
    logger.trace(s"Looking for the next snapshot in staging bulk storage after $afterTimestamp")
    acsSnapshotStagingProgress.readLatestProcessedSnapshotTimestamp
      .flatMap {
        case None =>
          logger.debug(
            s"No snapshot in staging bulk storage yet"
          )
          Future.successful(None)
        case Some(ts) if ts.timestamp <= afterTimestamp =>
          logger.debug(
            s"Latest snapshot in staging bulk storage is at ${ts.timestamp}, which is not after the requested timestamp $afterTimestamp"
          )
          Future.successful(None)
        case Some(ts) if afterTimestamp == CantonTimestamp.MinValue =>
          logger.debug(
            "snapshots exist in staging bulk storage, but `afterTimestamp` is at minimum value, finding the first snapshot expected in staging bulk storage"
          )
          getFirstAcsSnapshotTimestampAfterGenesis
            .map {
              case None =>
                throw Status.INTERNAL
                  .withDescription(
                    "Snapshots should exist in bulk storage, but first snapshot timestamp could not be found in kv store"
                  )
                  .asRuntimeException()
              case Some(firstSnapshotTs) => Some(firstSnapshotTs)
            }
        case Some(ts) =>
          logger.trace(
            s"Latest snapshot in staging bulk storage is at ${ts.timestamp}, which is after the requested timestamp $afterTimestamp, computing the next snapshot timestamp after $afterTimestamp (which is larger than min time of ${CantonTimestamp.MinValue})"
          )
          Future.successful(
            Some(
              storageConfig.computeSnapshotTimeAfter(
                afterTimestamp,
                storageConfig.bulkAcsSnapshotPeriodHours,
              )
            )
          )
      }
  }

  def getStagingObjectsForAcsSnapshotAt(
      timestamp: CantonTimestamp
  ): Future[AcsSnapshotObjects] = {
    getAcsSnapshotObjects(
      timestamp,
      stagingS3Connection,
      storageConfig,
      ScanStorageConfig.Encoding.all,
    )
  }

  def getStagingObjectsForUpdateHistorySegment(
      segment: UpdatesSegment
  ): Future[UpdateHistoryObjectsResponse] =
    getUpdateObjectsInSegment(segment, stagingS3Connection, ScanStorageConfig.Encoding.all)

  def getCommittedUpdatesBetweenDates(
      afterRecordTime: CantonTimestamp,
      atOrBeforeRecordTime: CantonTimestamp,
      limit: PageLimit,
      nextPageTokenO: Option[String],
      storageEncodings: NonEmptyList[ScanStorageConfig.Encoding],
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[UpdateHistoryObjectsResponse] =
    getUpdatesBetweenDatesFromBucket(
      afterRecordTime,
      atOrBeforeRecordTime,
      limit,
      nextPageTokenO,
      committedS3Connection,
      updateHistoryCommittedProgress.readLatestProcessedSegment,
      storageEncodings,
    )

  def getStagingSegmentStartingAt(
      startTimestamp: Option[CantonTimestamp]
  ): Future[Option[(CantonTimestamp, CantonTimestamp)]] = {
    val minTs = startTimestamp.getOrElse(CantonTimestamp.MinValue)

    updateHistoryStagingProgress.readLatestProcessedSegment
      .flatMap {
        case None =>
          logger.debug(
            s"No updates in staging bulk storage yet, returning None for segment starting at $minTs"
          )
          Future.successful(None)
        case Some(latestStagingTs) if latestStagingTs.toTimestamp.timestamp <= minTs =>
          logger.debug(
            s"Latest updates in staging bulk storage ending at $latestStagingTs, which is not after the requested timestamp $minTs, returning None for segment starting at $minTs"
          )
          Future.successful(None)
        case Some(latestStagingTs) =>
          getSegmentStartingAt(startTimestamp)
      }
  }

  def getStagingProgressTimestamp(): Future[CantonTimestamp] =
    progressTimestamp(updateHistoryStagingProgress, acsSnapshotStagingProgress)

  def getCommittedProgressTimestamp(): Future[CantonTimestamp] =
    progressTimestamp(updateHistoryCommittedProgress, acsSnapshotCommittedProgress)

  def getFirstOwnSegmentStart(): Future[Option[CantonTimestamp]] = firstOwnSegmentStart()

  private def progressTimestamp(
      updatesProgress: UpdateHistoryBulkStoragePersistentProgress,
      snapshotsProgress: AcsSnapshotBulkStoragePersistentProgress,
  ): Future[CantonTimestamp] = {
    for {
      updates <- updatesProgress.readLatestProcessedSegment
      snapshots <- snapshotsProgress.readLatestProcessedSnapshotTimestamp
    } yield {
      (updates, snapshots) match {
        case (Some(updatesSegment), Some(snapshotTs)) =>
          if (updatesSegment.toTimestamp.timestamp > snapshotTs.timestamp) {
            snapshotTs.timestamp
          } else {
            updatesSegment.toTimestamp.timestamp
          }
        case _ =>
          CantonTimestamp.MinValue
      }
    }
  }

  def getObjectChecksums(
      objectKeys: Seq[String]
  ): Future[Seq[Option[String]]] = {
    for {
      committed <- committedS3Connection.getChecksums(objectKeys)
      staging <- stagingS3Connection.getChecksums(objectKeys)
    } yield {
      objectKeys.map { key =>
        committed.find(_.key == key).orElse(staging.find(_.key == key)).map(_.checksum)
      }
    }
  }

  private def getSegmentStartingAt(
      startTimestamp: Option[CantonTimestamp]
  ): Future[Option[(CantonTimestamp, CantonTimestamp)]] =
    startTimestamp match {
      case None =>
        getFirstAcsSnapshotTimestampAfterGenesis
          .map(
            _.map(firstAcs => (CantonTimestamp.MinValue, firstAcs))
          )
      case Some(ts) =>
        Future.successful(
          Some((ts, storageConfig.computeBulkSnapshotTimeAfter(ts)))
        )
    }

  /** Checks if a key in bulk storage matches one of the given [[storageEncodings]]. */
  private def keyMatchesStorageEncodings(
      prefix: String,
      storageEncodings: NonEmptyList[ScanStorageConfig.Encoding],
  )(key: String): Boolean =
    storageEncodings.exists(_.storageKeyRegex(prefix).matches(key))

  private def getAcsSnapshotObjects(
      timestamp: CantonTimestamp,
      s3Connection: S3BucketConnection,
      storageConfig: ScanStorageConfig,
      storageEncodings: NonEmptyList[ScanStorageConfig.Encoding],
  ): Future[AcsSnapshotObjects] = {
    for {
      objects <- s3Connection
        // A single object currently holds ~700K contracts, we apply a Limit just for safety,
        // but we don't expect to get anywhere near 1000 such objects in the foreseeable future
        // (hence the HardLimit, just as a safety precaution).
        .listObjects(
          storageConfig.findSegmentFolderPrefixByStartTimestamp(timestamp),
          keyMatchesStorageEncodings("ACS", storageEncodings),
          HardLimit.tryCreate(Limit.DefaultMaxPageSize),
        )
      objectsWithChecksums <- s3Connection.getChecksums(objects)
    } yield {
      if (objects.isEmpty) {
        throw Status.NOT_FOUND
          .withDescription(
            s"No snapshot objects found in bulk storage at expected timestamp $timestamp, this may be because the timestamp is before network genesis"
          )
          .asRuntimeException()
      }
      logger.trace(
        s"Found snapshot in bulk storage at timestamp $timestamp, with objects: ${objects.mkString(", ")}"
      )
      AcsSnapshotObjects(timestamp, objectsWithChecksums)
    }
  }

  private def getFirstAcsSnapshotTimestampAfterGenesis: Future[Option[CantonTimestamp]] =
    acsSnapshotStagingProgress.readFirstSnapshotTimestamp.map(
      _.map(_.timestamp)
    )

  private def getUpdatesBetweenDatesFromBucket(
      afterRecordTime: CantonTimestamp,
      atOrBeforeRecordTime: CantonTimestamp,
      limit: PageLimit,
      nextPageTokenO: Option[String],
      s3Connection: S3BucketConnection,
      readLatestProcessedSegment: => Future[Option[UpdatesSegment]],
      storageEncodings: NonEmptyList[ScanStorageConfig.Encoding],
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[UpdateHistoryObjectsResponse] = {

    def isFolderInRange(folder: String): Boolean = {
      storageConfig.getStartAndEndTimestampsForFolder(folder) match {
        case Left(err) =>
          throw io.grpc.Status.INTERNAL
            .withDescription(
              s"Cannot parse folder name $folder, error: $err"
            )
            .asRuntimeException()
        case Right((folderStart, folderEnd)) =>
          folderStart < atOrBeforeRecordTime && folderEnd > afterRecordTime
      }
    }

    def isFolderFullyDumped(folder: String, lastSegmentEnd: CantonTimestamp): Boolean = {
      storageConfig.getStartAndEndTimestampsForFolder(folder) match {
        case Left(err) =>
          throw io.grpc.Status.INTERNAL
            .withDescription(
              s"Cannot parse folder name $folder, error: $err"
            )
            .asRuntimeException()
        case Right((folderStart, folderEnd)) =>
          folderEnd <= lastSegmentEnd
      }
    }

    def paginationFilter(folder: String): Boolean = {
      nextPageTokenO match {
        case None => true
        case Some(token) => folder > token
      }
    }

    def folderFilter(storageCaughtUpTo: Option[UpdatesSegment])(folder: String): Boolean = {
      storageCaughtUpTo match {
        case None =>
          false
        case Some(segment) =>
          isFolderInRange(folder) && paginationFilter(folder) && isFolderFullyDumped(
            folder,
            segment.toTimestamp.timestamp,
          )
      }
    }

    // TODO(#3429): Make sure to properly document the case where the user asked for an end timestamp that is later than what we have in storage.
    // Specifically: we still return the last folder as a "next page token" in that case, and in the next page, we return an empty result.
    def getNextPageToken(
        objKeys: Seq[String],
        storageCaughtUpTo: Option[UpdatesSegment],
    ): Option[String] = {
      /* We return a next page token when:
         - we found some objects to return (i.e. objKeys is non-empty), and the end time in the last folder we
           listed is before the atOrBeforeRecordTime (i.e. there are more folders to list that are in range, but we stopped listing due to the limit. We then use the last folder as the next page token)
         - or -
         - we found no objects to return, but the requested end time is later than the latest dumped data, so we want to signal the user to try again later
       */
      objKeys.lastOption.fold(
        storageCaughtUpTo match {
          case None => nextPageTokenO
          case Some(segment) =>
            if (segment.toTimestamp.timestamp < atOrBeforeRecordTime) {
              // We have dumped data up to a segment that ends before the requested end time, so return the current nextPageToken again, to be retried later
              nextPageTokenO
            } else {
              // We have dumped data up to a segment that ends after the requested end time, so we do not return a next page token, as there is no more data to list for this request
              None
            }
        }
      )(lastObjKey => {
        val lastFolder = lastObjKey.substring(0, lastObjKey.lastIndexOf('/') + 1)
        storageConfig.getStartAndEndTimestampsForFolder(lastFolder) match {
          case Left(err) =>
            throw io.grpc.Status.INTERNAL
              .withDescription(
                s"Cannot parse last folder name for next page token: $lastFolder, error: $err"
              )
              .asRuntimeException()
          case Right((_, folderEnd)) =>
            if (folderEnd < atOrBeforeRecordTime) {
              Some(lastFolder)
            } else {
              None
            }
        }
      })
    }

    def getFolderUpdateObjectsUpToLimit(folders: Seq[String]): Future[Seq[String]] = {
      folders
        .foldLeft(Future.successful((Seq.empty[String], limit.limit))) {
          case (futFolderState, folder) =>
            futFolderState.flatMap { case (folderAcc, folderLimit) =>
              if (folderLimit <= 0) {
                Future.successful((folderAcc, folderLimit))
              } else {
                getUpdateObjectsInFolder(s3Connection, folder, storageEncodings).map { folderObjs =>
                  if (folderObjs.size > folderLimit) {
                    // Folder would exceed the limit; omit it entirely (and stop adding more by making the limit 0)
                    if (folderAcc.isEmpty) {
                      throw io.grpc.Status.INVALID_ARGUMENT
                        .withDescription(
                          s"Limit of ${limit.limit} is too low to return any objects, even from a single folder of objects"
                        )
                        .asRuntimeException()
                    }
                    (folderAcc, 0)
                  } else {
                    (folderAcc ++ folderObjs, folderLimit - folderObjs.size)
                  }
                }
              }
            }
        }
        .map(_._1)
    }

    for {
      // We first read the storageCaughtUpTo value once here, and then use it for filtering folders and computing the next page token, to avoid races where the value moves in between those operations.
      storageCaughtUpTo <- readLatestProcessedSegment
      nextFolders <- s3Connection.listFolders(folderFilter(storageCaughtUpTo), limit)
      objKeys <- getFolderUpdateObjectsUpToLimit(nextFolders)
      objectsWithChecksums <- s3Connection.getChecksums(objKeys)
      nextPageTokenO = getNextPageToken(objKeys, storageCaughtUpTo)
    } yield {
      UpdateHistoryObjectsResponse(
        objectsWithChecksums,
        nextPageTokenO,
      )
    }
  }

  private def getUpdateObjectsInSegment(
      segment: UpdatesSegment,
      s3Connection: S3BucketConnection,
      storageEncodings: NonEmptyList[ScanStorageConfig.Encoding],
  ): Future[UpdateHistoryObjectsResponse] = {
    val folder = storageConfig.getSegmentFolder(
      segment.fromTimestamp.timestamp,
      Some(segment.toTimestamp.timestamp),
    )
    getUpdateObjectsInFolder(s3Connection, folder, storageEncodings)
      .flatMap(s3Connection.getChecksums)
      .map { objectsWithChecksums =>
        if (objectsWithChecksums.isEmpty) {
          throw Status.NOT_FOUND
            .withDescription(
              s"No update objects found in bulk storage for segment $segment"
            )
            .asRuntimeException()
        }
        logger.trace(
          s"Found update objects in bulk storage for segment $segment, with objects: ${objectsWithChecksums.map(_.key).mkString(", ")}"
        )
        UpdateHistoryObjectsResponse(
          objectsWithChecksums,
          None,
        )
      }
  }

  private def getUpdateObjectsInFolder(
      s3Connection: S3BucketConnection,
      folder: String,
      storageEncodings: NonEmptyList[ScanStorageConfig.Encoding],
  ): Future[Seq[String]] = s3Connection.listObjects(
    prefix = folder,
    keyMatchesStorageEncodings("updates", storageEncodings),
    HardLimit.tryCreate(Limit.DefaultMaxPageSize),
  )

}
