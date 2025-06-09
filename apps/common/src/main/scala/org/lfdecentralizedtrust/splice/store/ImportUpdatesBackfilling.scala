// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.store.ImportUpdatesBackfilling.{
  DestinationImportUpdates,
  DestinationImportUpdatesBackfillingInfo,
  Outcome,
  SourceImportUpdates,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.ImportUpdatesBackfilling.Outcome.{
  BackfillingIsComplete,
  MoreWorkAvailableLater,
  MoreWorkAvailableNow,
}

import scala.concurrent.{ExecutionContext, Future}

/** Copies import updates from a source to a destination history.
  *
  * "Import updates" are updates that represent the ACS right before a hard domain migration,
  * and are imported into the participant right after a hard domain migration.
  * They all have a record time of [[CantonTimestamp.MinValue]].
  *
  * Notes:
  * - This should have been part of [[HistoryBackfilling]], but that backfilling had already finished
  *   in production, so we need a separate process to fill in the missing import updates.
  */
final class ImportUpdatesBackfilling[T](
    destination: DestinationImportUpdates[T],
    source: SourceImportUpdates[T],
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
  def backfillImportUpdates()(implicit tc: TraceContext): Future[Outcome] = {
    if (!source.isReady) {
      logger.info("Source import updates are not ready, skipping backfill for now")
      Future.successful(MoreWorkAvailableLater)
    } else if (!destination.isReady) {
      logger.info("Destination import updates are not ready, skipping backfill for now")
      Future.successful(MoreWorkAvailableLater)
    } else {
      for {
        backfillingInfoO <- destination.importUpdatesBackfillingInfo
        outcome <- backfillingInfoO match {
          case None =>
            // Do not backfill while there's absolutely no data, otherwise we don't know where to start backfilling
            logger.info(
              "Destination import updates is not ready, skipping backfilling for now"
            )
            Future.successful(MoreWorkAvailableLater)
          case Some(backfillingInfo) =>
            backfillMigrationId(backfillingInfo)
        }
      } yield outcome
    }
  }

  private def backfillMigrationId(
      destInfo: DestinationImportUpdatesBackfillingInfo
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
      destInfo: DestinationImportUpdatesBackfillingInfo,
  )(implicit tc: TraceContext): Future[Outcome] = {
    logger.debug(
      s"backfillMigrationId(srcInfo=${srcInfo}, destInfo=${destInfo})"
    )
    val migrationId = destInfo.migrationId

    if (!srcInfo.importUpdatesComplete) {
      logger.debug(
        s"Source is not complete, not backfilling."
      )
      Future.successful(MoreWorkAvailableLater)
    } else {
      (srcInfo.lastImportUpdateId, destInfo.lastUpdateId, srcInfo.previousMigrationId) match {
        case (None, None, None) =>
          logger.info(
            s"Source is complete for migration $migrationId, there are no import updates on that migration, and there is no previous migration. " +
              "Considering import update backfilling as complete."
          )
          destination.markImportUpdatesBackfillingComplete().map { _ =>
            BackfillingIsComplete
          }
        case (Some(srcLastUpdateId), Some(destLastUpdateId), Some(srcPrevMigrationId))
            if destLastUpdateId == srcLastUpdateId =>
          logger.info(
            s"No more import updates to backfill for migration ${migrationId}, continuing with previous migration id ${srcPrevMigrationId}"
          )
          backfillMigrationId(
            DestinationImportUpdatesBackfillingInfo(srcPrevMigrationId, None)
          )
        case (Some(srcLastUpdateId), Some(destLastUpdateId), _)
            if destLastUpdateId < srcLastUpdateId =>
          logger.debug(
            s"Continuing to backfill import updates for migration id $migrationId from $destLastUpdateId until $srcLastUpdateId"
          )
          copyImportUpdates(migrationId, destLastUpdateId)
        case (Some(srcLastUpdateId), None, _) =>
          logger.debug(
            s"Starting to backfill import updates for migration id $migrationId from the beginning until $srcLastUpdateId"
          )
          copyImportUpdates(migrationId, destLastUpdateId = "")
        case _ =>
          logger.error(
            s"Unexpected import update backfilling state: srcInfo=$srcInfo, destInfo=$destInfo"
          )
          Future.successful(MoreWorkAvailableLater)
      }

    }
  }

  private def copyImportUpdates(migrationId: Long, destLastUpdateId: String)(implicit
      tc: TraceContext
  ): Future[Outcome] = {
    for {
      items <- source.importUpdates(migrationId, destLastUpdateId, batchSize)
      insertResult <- destination.insertImportUpdates(migrationId, items)
    } yield MoreWorkAvailableNow(insertResult)
  }
}

object ImportUpdatesBackfilling {

  sealed trait Outcome extends Product with Serializable

  object Outcome {

    /** Backfilling is not complete, and there is more work to do right now.
      * Call backfill() again immediately.
      */
    final case class MoreWorkAvailableNow(
        workDone: ImportUpdatesBackfilling.DestinationImportUpdates.InsertResult
    ) extends Outcome

    /** Backfilling is not complete, but cannot proceed right now.
      * Call backfill() again after a short delay.
      */
    final case object MoreWorkAvailableLater extends Outcome

    /** Backfilling is fully complete.
      * Stop calling backfill(), it won't do anything useful ever again.
      */
    final case object BackfillingIsComplete extends Outcome
  }

  /** Information about the point at which backfilling is currently inserting data.
    *
    * @param migrationId   The migration id that is currently being backfilled
    * @param lastUpdateId  The id of the last import update that exists on the given migration,
    *                      where import updates are ordered by update id.
    */
  final case class DestinationImportUpdatesBackfillingInfo(
      migrationId: Long,
      lastUpdateId: Option[String],
  )

  trait SourceImportUpdates[T] {

    def isReady: Boolean

    /** Returns metadata for the given migration id.
      * Returns None if data for the given migration id is not yet available.
      */
    def migrationInfo(migrationId: Long)(implicit
        tc: TraceContext
    ): Future[Option[SourceMigrationInfo]]

    /** A batch of import updates from the given migration, sorted by update id,
      * having an update id strictly greater than `afterUpdateId`
      */
    def importUpdates(
        migrationId: Long,
        afterUpdateId: String,
        count: Int,
    )(implicit tc: TraceContext): Future[Seq[T]]
  }

  trait DestinationImportUpdates[T] {

    def isReady: Boolean

    /** Returns information about the point at which backfilling is currently inserting data.
      */
    def importUpdatesBackfillingInfo(implicit
        tc: TraceContext
    ): Future[Option[DestinationImportUpdatesBackfillingInfo]]

    /** Insert the given sequence of import updates.
      */
    def insertImportUpdates(migrationId: Long, items: Seq[T])(implicit
        tc: TraceContext
    ): Future[DestinationImportUpdates.InsertResult]

    /** Explicitly marks the backfilling as complete */
    def markImportUpdatesBackfillingComplete()(implicit tc: TraceContext): Future[Unit]
  }
  object DestinationImportUpdates {
    case class InsertResult(
        migrationId: Long,
        backfilledContracts: Long,
    )
  }
}
