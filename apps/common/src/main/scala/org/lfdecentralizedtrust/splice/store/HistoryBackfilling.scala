// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.Outcome.{
  BackfillingIsComplete,
  MoreWorkAvailableLater,
  MoreWorkAvailableNow,
}
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.{
  DestinationBackfillingInfo,
  DestinationHistory,
  Outcome,
  SourceHistory,
  SourceMigrationInfo,
}
import org.lfdecentralizedtrust.splice.util.DomainRecordTimeRange
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** Copies items from a source to a destination history.
  * Items are only added "to the left" (before any existing items in the destination history),
  * and such that there are no holes in the history.
  *
  * Notes:
  * - History is usually updated "on the right" by ingesting live updates (e.g., the participant update stream).
  *   This ingestion consumes data from all connected domains and is offset-based.
  * - Data in the update stream is only partially ordered (by domain and record time). Updates from different domains
  *   are not ordered with respect to each other.
  * - Because of the above, record times are not monotonically increasing in the offset-based update stream.
  *   They are only monotonically increasing within a domain.
  *
  * Therefore, the backfilling process is domain-based, using the following approach:
  *   for each migration id:
  *     for each domain:
  *       find the oldest record time in the destination history
  *       fetch N previous records from the source history
  *       insert these records into the destination history
  */
final class HistoryBackfilling[T](
    destination: DestinationHistory[T],
    source: SourceHistory[T],
    currentMigrationId: Long,
    batchSize: Int,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging {

  /** Backfill a small part of the destination history, making sure that the destination history won't have any gaps.
    *
    * It is intended that this function is called repeatedly until it returns `BackfillingIsComplete`,
    * e.g., from a PollingTrigger.
    *
    * This function is *NOT* safe to call concurrently from multiple threads.
    */
  def backfill()(implicit tc: TraceContext): Future[Outcome] = {
    if (!source.isReady) {
      logger.info("Source history is not ready, skipping backfill for now")
      Future.successful(MoreWorkAvailableLater)
    } else if (!destination.isReady) {
      logger.info("Destination history is not ready, skipping backfill for now")
      Future.successful(MoreWorkAvailableLater)
    } else {
      for {
        backfillingInfoO <- destination.backfillingInfo
        outcome <- backfillingInfoO match {
          case None =>
            // Do not backfill while there's absolutely no data, otherwise we don't know where to start backfilling
            logger.info(
              "Destination backfilling is not ready, skipping backfilling for now"
            )
            Future.successful(MoreWorkAvailableLater)
          case Some(backfillingInfo) =>
            backfillMigrationId(backfillingInfo)
        }
      } yield outcome
    }
  }

  private def backfillMigrationId(
      destInfo: DestinationBackfillingInfo
  )(implicit tc: TraceContext): Future[Outcome] = {
    for {
      srcInfo <- source.migrationInfo(destInfo.migrationId)
      migrationId = destInfo.migrationId
      outcome <- srcInfo match {
        case None =>
          logger.debug(
            s"Source is not ready for migration id $migrationId, skipping backfill for now"
          )
          Future.successful(MoreWorkAvailableLater)
        case Some(srcInfo) =>
          logger.debug(s"Backfilling migration id $migrationId")
          backfillMigrationId(
            srcInfo,
            destInfo,
          )
      }
    } yield outcome
  }

  private def backfillMigrationId(
      srcInfo: SourceMigrationInfo,
      destInfo: DestinationBackfillingInfo,
  )(implicit tc: TraceContext): Future[Outcome] = {
    logger.debug(
      s"backfillMigrationId(srcInfo=${srcInfo}, destInfo=${destInfo})"
    )
    val migrationId = destInfo.migrationId
    // For each remote domain, compute the record time from which we can backfill items
    val backfillFrom = getBackfillUpdatesBefore(
      destInfo.backfilledAt,
      srcInfo.recordTimeRange,
      migrationId,
    )
    for {
      // For UX reasons, we pick the domain that is most behind in history, and backfill from there
      result <- backfillFrom.recordTimes.headOption match {
        case Some((synchronizerId, backfillFrom)) =>
          logger.info(
            s"Backfilling domain ${synchronizerId} from ${backfillFrom} for migration ${migrationId}"
          )
          backfillDomain(migrationId, synchronizerId, backfillFrom).map { insertResult =>
            MoreWorkAvailableNow(insertResult)
          }
        case None =>
          if (backfillFrom.missingData) {
            logger.debug(
              s"Some domains are not ready for backfilling, skipping backfill for now"
            )
            Future.successful(MoreWorkAvailableLater)
          } else if (srcInfo.complete) {
            srcInfo.previousMigrationId match {
              case Some(prevMigrationId) =>
                logger.info(
                  s"No more data to backfill for migration ${migrationId}, continuing with previous migration id ${prevMigrationId}"
                )
                backfillMigrationId(
                  DestinationBackfillingInfo(prevMigrationId, Map.empty)
                )
              case None =>
                logger.info(
                  s"No more data to backfill for migration ${migrationId}, and there is no migration id before ${migrationId}. " +
                    "This history won't need any further backfilling."
                )
                destination.markBackfillingComplete().map { _ =>
                  BackfillingIsComplete
                }
            }
          } else {
            logger.info(
              s"No more data to backfill for migration ${migrationId} right now, but source is not complete yet"
            )
            Future.successful(MoreWorkAvailableLater)
          }
      }
    } yield result
  }

  /** @param recordTimes  The record times from which we should backfill for each domain.
    * @param missingData  True if there are domains for which we do not know where to backfill from yet.
    */
  case class BackfillUpdatesBefore(
      recordTimes: Seq[(SynchronizerId, CantonTimestamp)],
      missingData: Boolean,
  )
  def getBackfillUpdatesBefore(
      destRecordTimes: Map[SynchronizerId, CantonTimestamp],
      srcRecordTimes: Map[SynchronizerId, DomainRecordTimeRange],
      migrationId: Long,
  )(implicit tc: TraceContext): BackfillUpdatesBefore = {
    logger.debug(
      s"backfillFromByDomain(destRecordTimes=${destRecordTimes}, srcRecordTimes=${srcRecordTimes}, migrationId=${migrationId}"
    )
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    var missingData = false
    val result = srcRecordTimes
      .map {
        case (synchronizerId, srcRecordTime) => {
          val backfillFrom = destRecordTimes.get(synchronizerId) match {
            case Some(destRecordTime) =>
              // Both source and destination history have data for this domain
              if (destRecordTime == srcRecordTime.min) {
                // Source and destination history are both at the same record time, nothing to do
                logger.debug(
                  s"Source and destination history are both at the same record time: domain ${synchronizerId}, migration ${migrationId}, time ${destRecordTime}"
                )
                None
              } else if (destRecordTime > srcRecordTime.min) {
                // Destination history has less data than source history, start backfilling from the oldest destination item
                logger.debug(
                  s"Destination history has less data than source history: domain ${synchronizerId}, migration ${migrationId}, dest min ${destRecordTime}, src min ${srcRecordTime.min}"
                )
                Some(destRecordTime)
              } else {
                // Destination history has more data than source history?!?
                logger.error(
                  s"Destination history has more data than source history: domain ${synchronizerId}, migration ${migrationId}, dest min ${destRecordTime}, src min ${srcRecordTime.min}"
                )
                None
              }
            case None =>
              // Destination doesn't contain any items for this domain+migration id
              if (migrationId < currentMigrationId) {
                // Destination ingestion is working on `currentMigrationId` and won't touch this migration id,
                // so we can safely backfill from the newest item in the source history.
                logger.debug(
                  s"No destination data for domain ${synchronizerId}, migration ${migrationId}. Backfilling from the newest item."
                )
                Some(CantonTimestamp.MaxValue)
              } else {
                // We're trying to backfill data for the currently active migration, i.e., the one that is being ingested.
                // To avoid race conditions, we skip backfilling this domain id until we have ingested the first item
                // from which we then can backfill.
                logger.debug(
                  s"No destination data for domain ${synchronizerId}, migration ${migrationId}. Waiting until first item is ingested."
                )
                missingData = true
                None
              }
          }
          synchronizerId -> backfillFrom
        }
      }
      .toSeq
      .flatMap({
        case (synchronizerId, Some(backfillFrom)) => Some(synchronizerId -> backfillFrom)
        case _ => None
      })
      .sortBy(_._2)
    BackfillUpdatesBefore(result, missingData)
  }

  def backfillDomain(
      migrationId: Long,
      synchronizerId: SynchronizerId,
      backfillFrom: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[HistoryBackfilling.DestinationHistory.InsertResult] = {
    for {
      items <- source.items(migrationId, synchronizerId, backfillFrom, batchSize)
      last <- destination.insert(migrationId, synchronizerId, items)
    } yield {
      logger.debug(
        s"Backfilled ${items.size} items for domain ${synchronizerId} in migration ${migrationId} before record time ${backfillFrom}"
      )
      last
    }
  }

}

object HistoryBackfilling {

  sealed trait Outcome extends Product with Serializable

  object Outcome {

    /** Backfilling is not complete, and there is more work to do right now.
      * Call backfill() again immediately.
      */
    final case class MoreWorkAvailableNow(workDone: DestinationHistory.InsertResult) extends Outcome

    /** Backfilling is not complete, but cannot proceed right now.
      * Call backfill() again after a short delay.
      */
    final case object MoreWorkAvailableLater extends Outcome

    /** Backfilling is fully complete.
      * Stop calling backfill(), it won't do anything useful ever again.
      */
    final case object BackfillingIsComplete extends Outcome
  }

  /** Metadata for a given migration id.
    *
    * @param previousMigrationId The migration id before the given migration id, if any.
    *                            None if the given migration id is the beginning of known history.
    * @param recordTimeRange     All domains that produced history items in the given migration id,
    *                            along with the record time of the newest and oldest history item associated with each domain.
    * @param lastImportUpdateId  The id of the last import update (where import updates are sorted by update id)
    *                            for the given migration id, if any.
    * @param complete            True if the backfilling for the given migration id is complete,
    *                            i.e., the history knows the first item for each domain in the given migration id.
    *                            We need this to decide when the backfilling is complete, because it might be difficult to
    *                            identify the first item of a migration otherwise.
    * @param importUpdatesComplete True if the import updates for the given migration id are complete.
    */
  final case class SourceMigrationInfo(
      previousMigrationId: Option[Long],
      recordTimeRange: Map[SynchronizerId, DomainRecordTimeRange],
      lastImportUpdateId: Option[String],
      complete: Boolean,
      importUpdatesComplete: Boolean,
  )

  /** Information about the point at which backfilling is currently inserting data.
    *
    * @param migrationId   The migration id that is currently being backfilled
    * @param backfilledAt  A vector clock specifying the earliest updates the destination history
    *                      has already consumed for backfilling.
    */
  final case class DestinationBackfillingInfo(
      migrationId: Long,
      backfilledAt: Map[SynchronizerId, CantonTimestamp],
  )

  trait SourceHistory[T] {

    def isReady: Boolean

    /** Returns metadata for the given migration id.
      * Returns None if data for the given migration id is not yet available.
      */
    def migrationInfo(migrationId: Long)(implicit
        tc: TraceContext
    ): Future[Option[SourceMigrationInfo]]

    /** The newest history items for the given domain and migration id,
      * with a record time before the given record time.
      */
    def items(
        migrationId: Long,
        synchronizerId: SynchronizerId,
        before: CantonTimestamp,
        count: Int,
    )(implicit tc: TraceContext): Future[Seq[T]]
  }

  trait DestinationHistory[T] {

    def isReady: Boolean

    /** Returns information about the point at which backfilling is currently inserting data.
      */
    def backfillingInfo(implicit
        tc: TraceContext
    ): Future[Option[DestinationBackfillingInfo]]

    /** Insert the given sequence of history items. The caller must make sure calls to this
      * function don't leave any gaps in the history.
      */
    def insert(migrationId: Long, synchronizerId: SynchronizerId, items: Seq[T])(implicit
        tc: TraceContext
    ): Future[DestinationHistory.InsertResult]

    /** Explicitly marks the backfilling as complete */
    def markBackfillingComplete()(implicit tc: TraceContext): Future[Unit]
  }
  object DestinationHistory {
    case class InsertResult(
        backfilledUpdates: Long,
        backfilledCreatedEvents: Long,
        backfilledExercisedEvents: Long,
        lastBackfilledRecordTime: CantonTimestamp,
    )
  }
}
