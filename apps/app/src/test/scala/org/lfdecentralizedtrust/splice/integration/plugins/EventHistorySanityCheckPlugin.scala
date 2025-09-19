package org.lfdecentralizedtrust.splice.integration.plugins

import cats.data.Chain
import org.lfdecentralizedtrust.splice.console.ScanAppBackendReference
import org.lfdecentralizedtrust.splice.environment.SpliceEnvironment
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.http.v0.definitions.EventHistoryItem
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2.members
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryReassignment.Event.members as reassignmentMembers
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTrigger
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil
import com.digitalasset.canton.ScalaFuturesWithPatience
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState
import org.scalatest.{Inspectors, LoneElement}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Span}

import scala.annotation.tailrec
import scala.concurrent.duration.*

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
    TraceContext.withNewTraceContext { implicit tc =>
      val initializedScans = environment.scans.local.filter(_.is_initialized)

      TriggerTestUtil
        .setTriggersWithin(
          triggersToPauseAtStart = initializedScans.map(scan =>
            // prevent races with the trigger when taking the forced manual snapshot
            scan.automation.trigger[AcsSnapshotTrigger]
          ),
          triggersToResumeAtStart = Seq(),
        ) {
          // This flag should have the same value on all scans
          if (initializedScans.exists(_.config.updateHistoryBackfillEnabled)) {
            initializedScans.foreach(waitUntilBackfillingComplete)
            compareEventHistories(initializedScans)
          } else {
            logger.debug("Backfilling is disabled, skipping event history comparison.")
          }
        }
    }
  }

  private def waitUntilBackfillingComplete(
      scan: ScanAppBackendReference
  )(implicit tc: TraceContext): Unit = {
    if (scan.config.updateHistoryBackfillEnabled) {
      // Backfilling is initialized by ScanHistoryBackfillingTrigger, which should take 1-2 trigger invocations
      // to complete.
      // Most integration tests use config transforms to reduce the polling interval to 1sec,
      // but some tests might not use the transforms and end up with the default long polling interval.
      val estimatedTimeUntilBackfillingComplete =
        2 * scan.config.automation.pollingInterval.underlying + 5.seconds

      if (estimatedTimeUntilBackfillingComplete > 30.seconds) {
        logger.warn(
          s"Scan ${scan.name} has a long polling interval of ${scan.config.automation.pollingInterval.underlying}. " +
            "Please disable EventHistorySanityCheckPlugin for this test or reduce the polling interval to avoid long waits."
        )
      }

      withClue(s"Waiting for backfilling to complete on ${scan.name}") {
        val patienceConfigForBackfillingInit: PatienceConfig =
          PatienceConfig(
            timeout = estimatedTimeUntilBackfillingComplete,
            interval = Span(100, Millis),
          )
        eventually {
          scan.automation.store.updateHistory
            .getBackfillingState()
            .futureValue should be(BackfillingState.Complete)
        }(
          patienceConfigForBackfillingInit,
          implicitly[org.scalatest.enablers.Retrying[org.scalatest.Assertion]],
          implicitly[org.scalactic.source.Position],
        )
      }
    } else {
      logger.debug("Backfilling is disabled, skipping wait.")
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
      val otherComparable = otherScanHistory.take(minSize)
      val founderComparable = founderHistory.take(minSize)
      val different = otherComparable.zipWithIndex.collect {
        case (otherItem, idx) if founderComparable(idx) != otherItem =>
          otherItem -> founderComparable(idx)
      }

      different should be(empty)
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
