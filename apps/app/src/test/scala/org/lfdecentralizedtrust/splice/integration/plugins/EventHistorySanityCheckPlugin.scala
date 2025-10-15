package org.lfdecentralizedtrust.splice.integration.plugins

import cats.data.Chain
import org.lfdecentralizedtrust.splice.console.ScanAppBackendReference
import org.lfdecentralizedtrust.splice.environment.SpliceEnvironment
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.http.v0.definitions.EventHistoryItem
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2.members
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryReassignment.Event.members as reassignmentMembers
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.digitalasset.canton.ScalaFuturesWithPatience
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.NamedLoggerFactory
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.scalatest.{Inspectors, LoneElement}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers

import scala.annotation.tailrec

class EventHistorySanityCheckPlugin(
    protected val loggerFactory: NamedLoggerFactory
) extends EnvironmentSetupPlugin[SpliceConfig, SpliceEnvironment]
    with Matchers
    with Eventually
    with Inspectors
    with ScalaFuturesWithPatience
    with LoneElement {

  override def beforeEnvironmentDestroyed(
      config: SpliceConfig,
      environment: SpliceTestConsoleEnvironment,
  ): Unit = {
    val initializedScans = environment.scans.local.filter(_.is_initialized)
    if (initializedScans.nonEmpty) {
      compareEventHistories(initializedScans)
    }
  }

  @tailrec
  private def paginateEventHistory(
      scan: ScanAppBackendReference,
      after: Option[(Long, String)],
      acc: Chain[EventHistoryItem],
  ): Chain[EventHistoryItem] = {
    val result = scan.getEventHistory(10, after, encoding = CompactJson)
    val newAcc = acc ++ Chain.fromSeq(result)
    if (result.isEmpty) newAcc
    else {
      result.lastOption.flatMap(eventCursor) match {
        case Some(nextAfter) =>
          paginateEventHistory(scan, Some(nextAfter), newAcc)
        case None => newAcc
      }
    }
  }

  private def compareEventHistories(
      scans: Seq[ScanAppBackendReference]
  ): Unit = {
    val (founders, others) = scans.partition(_.config.isFirstSv)
    val founder = founders.loneElement
    val founderHistory = paginateEventHistory(founder, None, Chain.empty).toVector
    forAll(others) { otherScan =>
      val otherScanHistory = paginateEventHistory(otherScan, None, Chain.empty).toVector
      val minSize = Math.min(founderHistory.size, otherScanHistory.size)
      val (otherComparable, otherRestDebug) = otherScanHistory.splitAt(minSize)
      val (founderComparable, founderRestDebug) = founderHistory.splitAt(minSize)
      val different = otherComparable
        .zip(founderComparable)
        .collect {
          case (otherItem, founderItem) if founderItem != otherItem =>
            otherItem -> founderItem
        }

      // custom error message to help debugging
      if (different.nonEmpty) {
        val debug = otherRestDebug.zipAll(founderRestDebug, None, None)
        fail(s"Mismatched Events: $different. The ones that come after are: $debug")
      }
    }
  }

  private def eventCursor(item: EventHistoryItem): Option[(Long, String)] = {
    val updateCursor = item.update.flatMap {
      case members.UpdateHistoryTransactionV2(tx) =>
        Some((tx.migrationId, tx.recordTime))
      case members.UpdateHistoryReassignment(reassignment) =>
        reassignment.event match {
          case reassignmentMembers.UpdateHistoryAssignment(event) =>
            Some((event.migrationId, reassignment.recordTime))
          case reassignmentMembers.UpdateHistoryUnassignment(event) =>
            Some((event.migrationId, reassignment.recordTime))
        }
    }
    updateCursor.orElse(item.verdict.map(v => (v.migrationId, v.recordTime)))
  }
}
