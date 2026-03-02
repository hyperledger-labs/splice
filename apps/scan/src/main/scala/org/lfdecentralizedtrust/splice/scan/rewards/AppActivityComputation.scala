// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}

import scala.annotation.unused
import scala.collection.immutable.SortedMap

/** Computation of app activity records, ie per featured app traffic weight for a single verdict
  * See CIP-104 for details.
  */
object AppActivityComputation {
  val MaxTrafficCostBytes: Long = 100L * 1024 * 1024 // 100 MB
}

class AppActivityComputation(
    override protected val loggerFactory: NamedLoggerFactory
) extends NamedLogging {

  def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)]
  )(implicit tc: TraceContext): Seq[DbAppActivityRecordStore.AppActivityRecordT] =
    summariesWithVerdicts.flatMap { case (summary, verdict) =>
      computeForSingleVerdict(summary, verdict)
    }

  // TBD
  protected def getFeaturedAppProviders(@unused recordTime: CantonTimestamp): Set[String] =
    Set.empty

  // TBD
  protected def getRoundNumber(@unused recordTime: CantonTimestamp): Long =
    0L

  private def computeForSingleVerdict(
      summary: DbScanVerdictStore.TrafficSummaryT,
      verdict: v30.Verdict,
  )(implicit tc: TraceContext): Option[DbAppActivityRecordStore.AppActivityRecordT] = {
    if (verdict.verdict != v30.VerdictResult.VERDICT_RESULT_ACCEPTED) None
    else if (summary.totalTrafficCost > AppActivityComputation.MaxTrafficCostBytes) {
      // Skip this record to guard against overflow in the weight formula:
      // envelope.trafficCost * totalConfirmationRequestTraffic.
      // 100 MB is well above any realistic confirmation request size.
      logger.warn(
        s"Skipping app activity record at ${summary.sequencingTime}: " +
          s"totalTrafficCost ${summary.totalTrafficCost} exceeds maximum ${AppActivityComputation.MaxTrafficCostBytes}"
      )
      None
    } else {
      val recordTime = summary.sequencingTime
      val featuredAppProviders = getFeaturedAppProviders(recordTime)
      val viewsMap = verdict.getTransactionViews.views

      val envelopesWithFeaturedAppConfirmers =
        summary.envelopeTrafficSummarys.flatMap { envelope =>
          val envelopeConfirmers = computeEnvelopeConfirmers(envelope, viewsMap)
          val featuredAppConfirmers = envelopeConfirmers.intersect(featuredAppProviders)
          Option.when(featuredAppConfirmers.nonEmpty)((envelope, featuredAppConfirmers))
        }

      val totalFeaturedAppEnvelopesTraffic: Long =
        envelopesWithFeaturedAppConfirmers.map(_._1.trafficCost).sum

      if (totalFeaturedAppEnvelopesTraffic == 0L) None
      else {
        val aggregatedWeights = computeAggregatedWeights(
          envelopesWithFeaturedAppConfirmers,
          summary.totalTrafficCost,
          totalFeaturedAppEnvelopesTraffic,
        )

        Some(
          DbAppActivityRecordStore.AppActivityRecordT(
            recordTime = recordTime,
            roundNumber = getRoundNumber(recordTime),
            appProviderParties = aggregatedWeights.keys.toSeq,
            appActivityWeights = aggregatedWeights.values.toSeq,
          )
        )
      }
    }
  }

  private def computeEnvelopeConfirmers(
      envelope: DbScanVerdictStore.EnvelopeT,
      viewsMap: Map[Int, v30.TransactionView],
  ): Set[String] =
    envelope.viewIds.foldLeft(Set.empty[String]) { (acc, viewId) =>
      viewsMap.get(viewId).fold(acc)(view => acc ++ view.confirmingParties.flatMap(_.parties))
    }

  /** Here it is important to accumulate per-app numerators and then
    * divide by totalFeaturedAppEnvelopesTraffic once at the end to reduce rounding error.
    */
  private def computeAggregatedWeights(
      envelopesWithFeaturedAppConfirmers: Seq[(DbScanVerdictStore.EnvelopeT, Set[String])],
      totalConfirmationRequestTraffic: Long,
      totalFeaturedAppEnvelopesTraffic: Long,
  ): SortedMap[String, Long] = {
    val perPartyNumerator = envelopesWithFeaturedAppConfirmers
      .foldLeft(SortedMap.empty[String, Long]) { case (acc, (envelope, featuredAppConfirmers)) =>
        val perEnvelopeNumerator: Long =
          (envelope.trafficCost * totalConfirmationRequestTraffic) / featuredAppConfirmers.size.toLong
        featuredAppConfirmers.foldLeft(acc) { (innerAcc, party) =>
          innerAcc.updatedWith(party)(prev => Some(prev.getOrElse(0L) + perEnvelopeNumerator))
        }
      }

    SortedMap.from(perPartyNumerator.view.mapValues(_ / totalFeaturedAppEnvelopesTraffic))
  }
}
