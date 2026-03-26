// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.pattern.after
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.PekkoRetryingService
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdateHistoryBulkStorage.UpdateHistoryObjectsResponse
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  HistoryMetrics,
  Limit,
  PageLimit,
  S3BucketConnection,
  TimestampWithMigrationId,
  UpdateHistory,
}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class UpdateHistoryBulkStorage(
    val storageConfig: ScanStorageConfig,
    val appConfig: BulkStorageConfig,
    val updateHistory: UpdateHistory,
    val kvProvider: ScanKeyValueProvider,
    val currentMigrationId: Long,
    val s3Connection: S3BucketConnection,
    val historyMetrics: HistoryMetrics,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning {

  private def getMigrationIdForAcsSnapshot(
      snapshotTimestamp: CantonTimestamp
  )(implicit tc: TraceContext): Future[Long] = {
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

  private def getSegmentEndAfter(
      ts: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[TimestampWithMigrationId] = {
    val endTs = storageConfig.computeBulkSnapshotTimeAfter(ts.timestamp)
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
  private def getFirstSegmentFromGenesis(implicit
      tc: TraceContext
  ): Future[Option[UpdatesSegment]] =
    for {
      firstUpdate <- updateHistory.getUpdatesWithoutImportUpdates(None, PageLimit.tryCreate(1))
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
  private def getFirstSegment(implicit tc: TraceContext): Future[Option[UpdatesSegment]] =
    kvProvider.getLatestUpdatesSegmentInBulkStorage().value.flatMap {
      case None => getFirstSegmentFromGenesis
      case Some(after) => getNextSegment(Some(after))
    }

  private def getNextSegment(
      afterO: Option[UpdatesSegment]
  )(implicit tc: TraceContext): Future[Option[UpdatesSegment]] =
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
      tc: TraceContext,
  ): Source[UpdatesSegment, Cancellable] = {

    // Wait for update history to initialize and for history backfilling to complete before starting bulk storage dumps
    val backfillingCompleteGate =
      Source
        .tick(0.seconds, appConfig.updatesPollingInterval.underlying, ())
        .mapAsync(1)(_ =>
          if (updateHistory.isReady)
            updateHistory.isHistoryBackfilled(currentMigrationId)
          else Future.successful(false)
        )
        .filter(identity)
        .take(1)

    backfillingCompleteGate.flatMap { _ =>
      Source
        .unfoldAsync(Option.empty[UpdatesSegment]) { current =>
          getNextSegment(current).flatMap {
            case Some(next) =>
              logger.info(s"Dumping next updates segment: $next")
              Future.successful(Some((Some(next), Some(next))))
            case None =>
              logger.debug(s"Next segment after $current not known yet, scheduling next attempt...")
              after(appConfig.updatesPollingInterval.underlying, actorSystem.scheduler)(
                Future.successful(Some((current, None)))
              )
          }
        }
        .collect { case Some(segment) => segment }
        .flatMapConcat(segment =>
          Source
            .single(segment)
            .via(
              UpdateHistorySegmentBulkStorage
                .asFlow(
                  storageConfig,
                  appConfig,
                  updateHistory,
                  s3Connection,
                  historyMetrics,
                  loggerFactory,
                )
                .map(keys => {
                  logger.debug(
                    s"Successfully dumped updates segment $segment to bulk storage, with object keys: $keys"
                  )
                  segment
                })
            )
        )
        .mapAsync(1) { segment =>
          historyMetrics.BulkStorage.latestUpdatesSegment.updateValue(segment.toTimestamp.timestamp)
          kvProvider.setLatestUpdatesSegmentInBulkStorage(segment).map(_ => segment)
        }
    }
  }

  def asRetryableService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[UpdatesSegment] = {
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      val src = mksrc()
      new PekkoRetryingService(
        src,
        Sink.ignore,
        automationConfig,
        backoffClock,
        "Update History Bulk Storage",
        retryProvider,
        loggerFactory,
      )
    }
  }

  def getUpdatesBetweenDates(
      afterRecordTime: CantonTimestamp,
      atOrBeforeRecordTime: CantonTimestamp,
      limit: PageLimit,
      nextPageTokenO: Option[String],
  )(implicit tc: TraceContext): Future[UpdateHistoryObjectsResponse] = {

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

    def getUpdateObjectsInFolder(folder: String): Future[Seq[String]] = s3Connection.listObjects(
      prefix = folder,
      _.matches(".*updates_\\d+\\.zstd"),
      HardLimit.tryCreate(Limit.DefaultMaxPageSize),
    )

    def getFolderFilter: Future[String => Boolean] = {
      kvProvider.getLatestUpdatesSegmentInBulkStorage().value.map {
        case None =>
          _ => false
        case Some(segment) =>
          folder => {
            isFolderInRange(folder) && paginationFilter(folder) && isFolderFullyDumped(
              folder,
              segment.toTimestamp.timestamp,
            )
          }
      }
    }

    // TODO(#3429): Make sure to properly document the case where the user asked for an end timestamp that is later than what we have in storage.
    // Specifically: we still return the last folder as a "next page token" in that case, and in the next page, we return an empty result.
    def getNextPageToken(objKeys: Seq[String]): Future[Option[String]] = {
      /* We return a next page token when:
         - we found some objects to return (i.e. objKeys is non-empty), and the end time in the last folder we
           listed is before the atOrBeforeRecordTime (i.e. there are more folders to list that are in range, but we stopped listing due to the limit. We then use the last folder as the next page token)
         - or -
         - we found no objects to return, but the requested end time is past the dumped data, so we want to signal the user to try again later
       */
      objKeys.lastOption.fold(
        // FIXME: there's actually a race here because we're reading this value multiple times, and it might move in between. We should read it only once!
        kvProvider.getLatestUpdatesSegmentInBulkStorage().value.map {
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
              Future.successful(Some(lastFolder))
            } else {
              Future.successful(None)
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
                getUpdateObjectsInFolder(folder).map { folderObjs =>
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
      folderFilter <- getFolderFilter
      nextFolders <- s3Connection.listFolders(folderFilter, limit)
      objKeys <- getFolderUpdateObjectsUpToLimit(nextFolders)
      objectsWithChecksums <- s3Connection.getChecksums(objKeys)
      nextPageTokenO <- getNextPageToken(objKeys)
    } yield {
      UpdateHistoryObjectsResponse(
        objectsWithChecksums,
        nextPageTokenO,
      )
    }
  }

}

object UpdateHistoryBulkStorage {
  case class UpdateHistoryObjectsResponse(
      objects: Seq[ObjectKeyAndChecksum],
      nextPageTokenO: Option[String],
  )
}
