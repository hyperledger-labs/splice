package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.console.ScanAppReference
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson
import definitions.EventHistoryItem
import definitions.UpdateHistoryItemV2.members.{
  UpdateHistoryReassignment,
  UpdateHistoryTransactionV2,
}
import definitions.UpdateHistoryReassignment.Event.members.{
  UpdateHistoryAssignment,
  UpdateHistoryUnassignment,
}

trait ScanTestUtil {

  def latestEventHistoryCursor(
      scan: ScanAppReference,
      startAfter: Option[(Long, String)] = None,
      pageLimit: Int = 1000,
  ): (Long, String) = {
    @annotation.tailrec
    def go(after: Option[(Long, String)], acc: Option[(Long, String)]): Option[(Long, String)] = {
      val page = scan.getEventHistory(
        count = pageLimit,
        after = after,
        encoding = CompactJson,
      )
      if (page.isEmpty) acc
      else {
        val next = page.lastOption.flatMap(cursorOf)
        val newAcc = next.orElse(acc)
        next match {
          case Some(c) => go(Some(c), newAcc)
          case None => newAcc
        }
      }
    }
    go(startAfter, None).getOrElse((0L, ""))
  }

  // (migrationId, recordTime) for an event item
  private def cursorOf(item: EventHistoryItem): Option[(Long, String)] = {
    val updateCursor: Option[(Long, String)] = item.update.flatMap {
      case UpdateHistoryTransactionV2(tx) =>
        Some((tx.migrationId, tx.recordTime))
      case UpdateHistoryReassignment(r) =>
        r.event match {
          case UpdateHistoryAssignment(ev) =>
            Some((ev.migrationId, r.recordTime))
          case UpdateHistoryUnassignment(ev) =>
            Some((ev.migrationId, r.recordTime))
        }
    }
    updateCursor.orElse(item.verdict.map(v => (v.migrationId, v.recordTime)))
  }
}
