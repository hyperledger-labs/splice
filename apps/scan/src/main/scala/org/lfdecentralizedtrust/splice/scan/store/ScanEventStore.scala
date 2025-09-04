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

  def getEventByUpdateId(updateId: String)(implicit tc: TraceContext): Future[Option[Event]] = {
    val fUpdate = updateHistory.getUpdate(updateId)
    val fVerdict = verdictStore.getVerdictByUpdateId(updateId)
    for {
      updateO <- fUpdate
      verdictO <- fVerdict
      verdictWithViewsO <- verdictO match {
        case Some(v) => verdictStore.listTransactionViews(v.rowId).map(views => Some((v, views)))
        case None => Future.successful(None)
      }
    } yield {
      if (updateO.isEmpty && verdictWithViewsO.isEmpty) None else Some((verdictWithViewsO, updateO))
    }
  }

  /** Simple Algo, useful for testing
    */
  def getEventsReference(
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
    val maxVerdictTimeF = verdictStore.maxVerdictRecordTime(currentMigrationId)
    val maxUpdateTimeF =
      verdictStore.maxUpdateRecordTime(updateHistory.historyId, currentMigrationId)

    for {
      verdicts <- verdictsF
      viewsByVerdictId <- Future
        .traverse(verdicts)(v => verdictStore.listTransactionViews(v.rowId).map(v.rowId -> _))
        .map(_.toMap)
      updatesAll <- updatesF
      maxVerdictO <- maxVerdictTimeF
      maxUpdateO <- maxUpdateTimeF
      updates = updatesAll
    } yield {
      def keyVerdict(v: VerdictT) = (v.migrationId, v.recordTime)
      def keyUpdate(u: TreeUpdateWithMigrationId) = (u.migrationId, u.update.update.recordTime)

      val capO: Option[CantonTimestamp] = (maxVerdictO, maxUpdateO) match {
        case (Some(v), Some(u)) => Some(if (v < u) v else u)
        case (Some(v), None) => Some(v)
        case (None, Some(u)) => Some(u)
        case _ => None
      }

      def allow(mig: Long, rt: CantonTimestamp): Boolean = {
        afterO match {
          case Some((afterMig, afterRt)) if mig == afterMig =>
            if (mig < currentMigrationId) rt > afterRt
            else rt > afterRt && capO.forall(rt <= _)
          case _ if mig < currentMigrationId =>
            // For prior migrations, stream everything in order
            rt > CantonTimestamp.MinValue
          case _ =>
            rt > CantonTimestamp.MinValue && capO.forall(rt <= _)
        }
      }

      val verdictByKey: Map[(Long, CantonTimestamp), Verdict] = verdicts
        .filter(v => allow(v.migrationId, v.recordTime))
        .map(v => keyVerdict(v) -> (v -> viewsByVerdictId.getOrElse(v.rowId, Seq.empty)))
        .toMap

      val updateByKey: Map[(Long, CantonTimestamp), TreeUpdateWithMigrationId] = updates
        .filter(u => allow(u.migrationId, u.update.update.recordTime))
        .map(u => keyUpdate(u) -> u)
        .toMap

      val keys = (verdictByKey.keySet union updateByKey.keySet).toSeq
        .sortBy(identity)

      keys.iterator
        .map(k => (verdictByKey.get(k), updateByKey.get(k)))
        .take(limit.limit)
        .toSeq
    }
  }
}
