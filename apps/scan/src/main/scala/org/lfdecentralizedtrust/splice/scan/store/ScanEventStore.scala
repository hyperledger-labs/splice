// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.store.TreeUpdateWithMigrationId
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.store.PageLimit
import scala.collection.immutable.SortedMap

import scala.concurrent.{ExecutionContext, Future}

/** Combines data from UpdateHistory store and DbScanVerdictStore, for events endpoints */
class ScanEventStore(
    val verdictStore: DbScanVerdictStore,
    val updateHistory: UpdateHistory,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  type VerdictT = DbScanVerdictStore.VerdictT
  type TransactionViewT = DbScanVerdictStore.TransactionViewT
  type Verdict = (VerdictT, Seq[TransactionViewT])
  type Event = (Option[Verdict], Option[TreeUpdateWithMigrationId])

  def getEventByUpdateId(
      updateId: String,
      currentMigrationId: Long,
  )(implicit tc: TraceContext): Future[Option[Event]] = {
    val fUpdate = updateHistory.getUpdate(updateId)
    val fVerdict = verdictStore.getVerdictByUpdateId(updateId)
    for {
      currentCap <- resolveCurrentMigrationCap(
        verdictStore.lastIngestedRecordTime,
        updateHistory.lastIngestedRecordTime,
        currentMigrationId,
      )
      updateO <- fUpdate
      verdictO <- fVerdict
      verdictWithViewsO <- verdictO match {
        case Some(v) => verdictStore.listTransactionViews(v.rowId).map(views => Some((v, views)))
        case None => Future.successful(None)
      }
    } yield {
      val isAllowed = ScanEventStore.allowF(afterO = None, currentMigrationId, currentCap)
      val updateAllowed = updateO.forall(u => isAllowed(u.migrationId, u.update.update.recordTime))
      val verdictAllowed = verdictO.forall(v => isAllowed(v.migrationId, v.recordTime))
      if (!updateAllowed || !verdictAllowed) None
      else if (updateO.isEmpty && verdictWithViewsO.isEmpty) None
      else Some((verdictWithViewsO, updateO))
    }
  }

  def getEvents(
      afterO: Option[(Long, CantonTimestamp)],
      currentMigrationId: Long,
      limit: PageLimit,
  )(implicit tc: TraceContext): Future[Seq[Event]] = {
    val verdictsF = verdictStore.listVerdicts(
      afterO = afterO,
      includeImportUpdates = false,
      limit = limit.limit,
    )
    val updatesF = updateHistory.getUpdatesWithoutImportUpdates(afterO, limit)

    for {
      currentCap <- resolveCurrentMigrationCap(
        verdictStore.lastIngestedRecordTime,
        updateHistory.lastIngestedRecordTime,
        currentMigrationId,
      )
      isAllowed = ScanEventStore.allowF(afterO, currentMigrationId, currentCap)
      verdicts <- verdictsF
      cappedVerdicts = verdicts.filter(v => isAllowed(v.migrationId, v.recordTime))
      // Fetch views only for filtered verdicts
      verdictsWithViews <- Future.traverse(cappedVerdicts)(v =>
        verdictStore.listTransactionViews(v.rowId).map(views => v -> views)
      )
      updatesAll <- updatesF
    } yield {
      val verdictEntries: Iterator[((Long, CantonTimestamp), Verdict)] =
        verdictsWithViews.iterator.map { case (v, views) =>
          val k = (v.migrationId, v.recordTime)
          k -> (v -> views)
        }

      val filteredUpdates =
        updatesAll.filter(u => isAllowed(u.migrationId, u.update.update.recordTime))

      val mergedSorted = {
        val fromUpdates = filteredUpdates.iterator.foldLeft(
          SortedMap.empty[
            (Long, CantonTimestamp),
            (Option[Verdict], Option[TreeUpdateWithMigrationId]),
          ]
        ) { case (acc, u) =>
          val k = (u.migrationId, u.update.update.recordTime)
          acc.updated(k, (None, Some(u)))
        }
        verdictEntries.foldLeft(fromUpdates) { case (acc, (k, v)) =>
          acc.get(k) match {
            case Some((_, uOpt)) => acc.updated(k, (Some(v), uOpt))
            case None => acc.updated(k, (Some(v), None))
          }
        }
      }

      mergedSorted.iterator
        .take(limit.limit)
        .map(_._2)
        .toSeq
    }
  }

  // Get values from in-memory refs, fallsback to DB read
  private def resolveCurrentMigrationCap(
      memVerdictRt: Option[CantonTimestamp],
      memUpdateRt: Option[CantonTimestamp],
      currentMigrationId: Long,
  )(implicit tc: TraceContext): Future[CantonTimestamp] = {
    val verdictF = memVerdictRt match {
      case some @ Some(_) => Future.successful(some)
      case None => verdictStore.maxVerdictRecordTime(currentMigrationId)
    }
    val updateF = memUpdateRt match {
      case some @ Some(_) => Future.successful(some)
      case None => verdictStore.maxUpdateRecordTime(updateHistory.historyId, currentMigrationId)
    }
    for {
      v <- verdictF
      u <- updateF
    } yield ScanEventStore.getCurrentMigrationCap(v, u)
  }
}

// Filtering logic extracted out for unit testing
object ScanEventStore {
  def allowF(
      afterO: Option[(Long, CantonTimestamp)],
      currentMigrationId: Long,
      currentMigrationCap: CantonTimestamp,
  )(mig: Long, rt: CantonTimestamp): Boolean = {
    afterO match {
      case Some((afterMig, afterRt)) if mig == afterMig =>
        if (mig < currentMigrationId) rt > afterRt
        else rt > afterRt && rt <= currentMigrationCap
      case _ if mig < currentMigrationId =>
        // For prior migrations, stream everything in order
        rt > CantonTimestamp.MinValue
      case _ =>
        rt > CantonTimestamp.MinValue && rt <= currentMigrationCap
    }
  }

  // For the currentMigrationId we expect data to be present in both tables
  // In case data in either table is missing for currentMigrationId, no events would be returned
  def getCurrentMigrationCap(
      verdictMaxRt: Option[CantonTimestamp],
      updateMaxRt: Option[CantonTimestamp],
  ): CantonTimestamp = (verdictMaxRt, updateMaxRt) match {
    case (Some(v), Some(u)) => if (v < u) v else u
    case _ => CantonTimestamp.MinValue
  }

}
