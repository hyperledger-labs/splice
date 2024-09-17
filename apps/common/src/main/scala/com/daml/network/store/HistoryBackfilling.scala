// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

import cats.kernel.Semigroup
import com.daml.network.store.HistoryBackfilling.Outcome.{
  BackfillingIsComplete,
  MoreWorkAvailableLater,
  MoreWorkAvailableNow,
}
import com.daml.network.store.HistoryBackfilling.{
  DestinationHistory,
  DomainRecordTimeRange,
  Outcome,
  SourceHistory,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
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
  *       find the oldest record time in the local history
  *       fetch N previous records from the source history
  *       insert these records into the local history
  */
final class HistoryBackfilling[T](
    destination: DestinationHistory[T],
    source: SourceHistory[T],
    batchSize: Int,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging {

  /** Backfill a small part of the local history, making sure that the local history won't have any gaps.
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
        destMigrationIdRange <- destination.migrationIdRange
        outcome <- destMigrationIdRange match {
          case None =>
            // Do not backfill while there's absolutely no data, otherwise we don't know where to start backfilling
            logger.info(
              "Destination migration id range is empty, skipping backfilling until there is some data"
            )
            Future.successful(MoreWorkAvailableLater)
          case Some(range) =>
            // Otherwise, start backfilling the first (oldest) migration id - since there are no gaps,
            // only the first migration id needs to be backfilled.
            backfillMigrationIdRange(range)
        }
      } yield outcome
    }
  }

  private def backfillMigrationIdRange(
      destMigrationIdRange: (Long, Long)
  )(implicit tc: TraceContext): Future[Outcome] = {
    // Start backfilling the oldest migration id - since there are no gaps,
    // all other migrations do not need to be backfilled.
    val migrationId = destMigrationIdRange._1
    for {
      destComplete <- destination.isBackfillingComplete(migrationId)
      prevMigrationIdOpt <- source.previousMigrationId(migrationId)
      outcome <- (destComplete, prevMigrationIdOpt) match {
        case (true, None) =>
          logger.info(
            s"Backfilling migration id $migrationId is complete, and there is no migration id before $migrationId. " +
              "This history won't need any further backfilling."
          )
          Future.successful(BackfillingIsComplete)
        case (true, Some(prevMigrationId)) =>
          logger.info(
            s"Backfilling migration id $migrationId is complete, continuing with previous migration id $prevMigrationId"
          )
          backfillMigrationId(prevMigrationId, destMigrationIdRange)
        case (false, _) =>
          logger.debug(s"Backfilling migration id $migrationId not complete yet")
          backfillMigrationId(migrationId, destMigrationIdRange)
      }
    } yield outcome
  }

  private def backfillMigrationId(
      migrationId: Long,
      destMigrationIdRange: (Long, Long),
  )(implicit tc: TraceContext): Future[Outcome] = {
    for {
      remoteRecordTimes <- source.recordTimeRange(migrationId)
      localRecordTimes <- destination.recordTimeRange(migrationId)
      sourceBackfillingComplete <- source.isBackfillingComplete(migrationId)

      // For each remote domain, compute the record time from which we can backfill items
      backfillFrom = backfillFromByDomain(
        localRecordTimes,
        remoteRecordTimes,
        migrationId,
        destMigrationIdRange,
      )

      // For UX reasons, we pick the domain that is most behind in history, and backfill from there
      result <- backfillFrom.headOption match {
        case Some((domainId, backfillFrom)) =>
          logger.info(
            s"Backfilling domain ${domainId} from ${backfillFrom} for migration ${migrationId}"
          )
          backfillDomain(migrationId, domainId, backfillFrom)
        case None =>
          if (sourceBackfillingComplete) {
            logger.info(
              s"No more data to backfill for migration ${migrationId}, marking as complete"
            )
            destination
              .markBackfillingComplete(migrationId)
              .map(_ => MoreWorkAvailableNow)
          } else {
            logger.info(
              s"No more data to backfill for migration ${migrationId} right now, but source is not complete yet"
            )
            Future.successful(MoreWorkAvailableLater)
          }
      }
    } yield result
  }

  def backfillFromByDomain(
      destRecordTimes: Map[DomainId, DomainRecordTimeRange],
      srcRecordTimes: Map[DomainId, DomainRecordTimeRange],
      migrationId: Long,
      destMigrationIdRange: (Long, Long),
  )(implicit tc: TraceContext): Seq[(DomainId, CantonTimestamp)] = {
    srcRecordTimes
      .map {
        case (domainId, srcRecordTime) => {
          val backfillFrom = destRecordTimes.get(domainId) match {
            case Some(destRecordTime) =>
              // Both source and destination history have data for this domain
              if (destRecordTime.min == srcRecordTime.min) {
                // Source and destination history are both at the same record time, nothing to do
                logger.trace(
                  s"Source and destination history are both at the same record time: domain ${domainId}, migration ${migrationId}, time ${destRecordTime.min}"
                )
                None
              } else if (destRecordTime.min > srcRecordTime.min) {
                // Destination history has less data than source history, start backfilling from the oldest destination item
                logger.trace(
                  s"Destination history has less data than source history: domain ${domainId}, migration ${migrationId}, dest min ${destRecordTime.min}, src min ${srcRecordTime.min}"
                )
                Some(destRecordTime.min)
              } else {
                // Destination history has more data than source history?!?
                logger.error(
                  s"Destination history has more data than source history: domain ${domainId}, migration ${migrationId}, dest min ${destRecordTime.min}, src min ${srcRecordTime.min}"
                )
                None
              }
            case None =>
              // Destination doesn't contain any items for this domain+migration id
              if (migrationId < destMigrationIdRange._2) {
                // Destination ingestion is working at `destMigrationIdRange._2` and won't touch this migration id,
                // so we can safely backfill from the newest item in the source history.
                logger.debug(
                  s"No local data for domain ${domainId}, migration ${migrationId}. Backfilling from the newest item."
                )
                Some(CantonTimestamp.MaxValue)
              } else {
                // Ingestion is not complete yet for this migration id, yet we do not have any item for this
                // domain in this migration id. To avoid race conditions, we skip backfilling this domain id
                // until we have ingested the first item from which we then can backfill.
                logger.debug(
                  s"No local data for domain ${domainId}, migration ${migrationId}. Waiting until first item is ingested."
                )
                None
              }
          }
          domainId -> backfillFrom
        }
      }
      .toSeq
      .flatMap({
        case (domainId, Some(backfillFrom)) => Some(domainId -> backfillFrom)
        case _ => None
      })
      .sortBy(_._2)
  }

  def backfillDomain(
      migrationId: Long,
      domainId: DomainId,
      backfillFrom: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Outcome] = {
    for {
      items <- source.items(migrationId, domainId, backfillFrom, batchSize)
      _ <- destination.insert(migrationId, domainId, items)
    } yield {
      logger.debug(
        s"Backfilled ${items.size} items for domain ${domainId} in migration ${migrationId} before record time ${backfillFrom}"
      )
      MoreWorkAvailableNow
    }
  }

}

object HistoryBackfilling {

  sealed trait Outcome extends Product with Serializable
  object Outcome {

    /** Backfilling is not complete, and there is more work to do right now.
      * Call backfill() again immediately.
      */
    final case object MoreWorkAvailableNow extends Outcome

    /** Backfilling is not complete, but cannot proceed right now.
      * Call backfill() again after a short delay.
      */
    final case object MoreWorkAvailableLater extends Outcome

    /** Backfilling is fully complete.
      * Stop calling backfill(), it won't do anything useful ever again.
      */
    final case object BackfillingIsComplete extends Outcome
  }

  final case class DomainRecordTimeRange(
      min: CantonTimestamp,
      max: CantonTimestamp,
  )
  implicit def domainTimeRangeSemigroupUnion: Semigroup[DomainRecordTimeRange] =
    (x: DomainRecordTimeRange, y: DomainRecordTimeRange) =>
      DomainRecordTimeRange(x.min min y.min, x.max max y.max)

  final case class MigrationInfo(
      previousMigrationId: Option[Long],
      recordTimeRange: Map[DomainId, DomainRecordTimeRange],
      complete: Boolean,
  )

  trait SourceHistory[T] {

    def isReady: Boolean

    /** Returns the migration id before the given id, if any.
      * Returns None if the given migration id is the beginning of known history.
      */
    def previousMigrationId(migrationId: Long)(implicit tc: TraceContext): Future[Option[Long]]

    /** All domains that produced history items in the given migration id,
      * along with the record time of the newest and oldest history item associated with each domain
      */
    def recordTimeRange(migrationId: Long)(implicit
        tc: TraceContext
    ): Future[Map[DomainId, DomainRecordTimeRange]]

    /** The newest history items for the given domain and migration id,
      * with a record time before the given record time.
      */
    def items(
        migrationId: Long,
        domainId: DomainId,
        before: CantonTimestamp,
        count: Int,
    )(implicit tc: TraceContext): Future[Seq[T]]

    /** True if the backfilling for given migration id is complete,
      * i.e., this history knows the first item for each domain in the given migration id.
      *
      * We need to explicitly track this, as there is no other marker for the first history item of a migration.
      * Without this, we don't know whether a source history could return more items in the future,
      * because it is still backfilling itself.
      */
    def isBackfillingComplete(migrationId: Long)(implicit tc: TraceContext): Future[Boolean]
  }

  trait DestinationHistory[T] {

    def isReady: Boolean

    /** The first and last migration id for which there is any data */
    def migrationIdRange(implicit tc: TraceContext): Future[Option[(Long, Long)]]

    /** All domains that produced history items in the given migration id,
      * along with the record time of the newest and oldest history item associated with each domain
      */
    def recordTimeRange(migrationId: Long)(implicit
        tc: TraceContext
    ): Future[Map[DomainId, DomainRecordTimeRange]]

    /** Insert the given sequence of history items. The caller must make sure calls to this
      * function don't leave any gaps in the history.
      */
    def insert(migrationId: Long, domainId: DomainId, items: Seq[T])(implicit
        tc: TraceContext
    ): Future[Unit]

    /** True if the backfilling for given migration id is complete,
      * i.e., this history knows the first item for each domain in the given migration id.
      */
    def isBackfillingComplete(migrationId: Long)(implicit tc: TraceContext): Future[Boolean]

    /** Explicitly marks the backfilling of the given migration id as complete */
    def markBackfillingComplete(migrationId: Long)(implicit tc: TraceContext): Future[Unit]
  }
}
