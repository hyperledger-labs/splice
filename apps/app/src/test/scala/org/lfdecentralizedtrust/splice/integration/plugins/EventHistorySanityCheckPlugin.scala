package org.lfdecentralizedtrust.splice.integration.plugins

import cats.data.Chain
import com.digitalasset.canton.ScalaFuturesWithPatience
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.NamedLoggerFactory
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_AddSv
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.console.ScanAppBackendReference
import org.lfdecentralizedtrust.splice.environment.SpliceEnvironment
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  EventHistoryItem,
  TreeEvent,
  UpdateHistoryItemV2,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2.members
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryReassignment.Event.members as reassignmentMembers
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, LoneElement}

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
    val completeFounderHistory = paginateEventHistory(founder, None, Chain.empty).toVector
    forAll(others) { otherScan =>
      // we have to exclude all history before the otherScan was onboarded because:
      // - they will be missing verdicts for everything that happened before they joined (we don't do backfilling yet)
      // - they will have extra verdicts that do not involve the DSO party as part of onboarding, so the founder doesn't have them
      val founderHistorySinceOtherOnboarding = completeFounderHistory
        .dropWhile(item =>
          !item.update.exists {
            case UpdateHistoryItemV2.members.UpdateHistoryReassignment(_) => false
            case UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx) =>
              tx.eventsById.exists(_._2 match {
                case TreeEvent.members.ExercisedEvent(exercised)
                    if exercised.choice == "DsoRules_AddSv" =>
                  DsoRules_AddSv
                    .fromJson(exercised.choiceArgument.noSpaces)
                    .newSvParty == otherScan
                    .getDsoInfo()
                    .svPartyId
                case _ => false
              })
          }
        )

      val otherStart = founderHistorySinceOtherOnboarding.headOption
        .flatMap(_.update)
        .getOrElse(
          throw new IllegalStateException(
            s"SV ${otherScan.config.svUser} doesn't appear in founder history"
          )
        ) match {
        case members.UpdateHistoryReassignment(_) =>
          throw new IllegalStateException("This is most certainly not a Reassignment")
        case members.UpdateHistoryTransactionV2(value) =>
          (value.migrationId, value.recordTime)
      }

      val otherScanHistory = paginateEventHistory(otherScan, Some(otherStart), Chain.empty).toVector
        // the mediator takes some time after onboarding until it starts producing verdicts
        .dropWhile(_.verdict.isEmpty)
      if (otherScanHistory.isEmpty) {
        throw new IllegalStateException(
          s"Scan ${otherScan.config.svUser} was empty, but that's shouldn't happen in tests."
        )
      }

      val founderHistoryToUse = founderHistorySinceOtherOnboarding.dropWhile(item =>
        item.verdict != otherScanHistory.headOption.flatMap(_.verdict)
      )
      val minSize = Math.min(founderHistoryToUse.size, otherScanHistory.size)
      val (otherComparable, otherRestDebug) = otherScanHistory.splitAt(minSize)
      val (founderComparable, founderRestDebug) = founderHistoryToUse.splitAt(minSize)
      val different = otherComparable
        .zip(founderComparable)
        .collect {
          case (otherItem, founderItem) if founderItem != otherItem =>
            otherItem -> founderItem
        }

      // custom error message to help debugging
      if (different.nonEmpty) {
        val debug: Seq[(Option[EventHistoryItem], Option[EventHistoryItem])] =
          otherRestDebug.map(Some(_)).zipAll(founderRestDebug.map(Some(_)), None, None)
        fail(s"Mismatched Events: $different. The ones that come after are: $debug")
      }
    }
  }

  private def eventCursor(item: EventHistoryItem): Option[(Long, String)] = {
    val updateCursor = item.update.flatMap {
      case UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx) =>
        Some((tx.migrationId, tx.recordTime))
      case UpdateHistoryItemV2.members.UpdateHistoryReassignment(reassignment) =>
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
