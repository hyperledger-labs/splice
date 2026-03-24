// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}

import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}

/** Computation of app activity records, ie per featured app traffic weight for a single verdict
  * See CIP-104 for details.
  */
object AppActivityComputation {
  val MaxTrafficCostBytes: Long = 100L * 1024 * 1024 // 100 MB
}

class AppActivityComputation(
    dataProvider: ScanRewardsReferenceStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  /** Compute app activity records for a batch of verdicts.
    *
    * Records are returned with verdictRowId = 0 as a placeholder.
    * The caller is responsible for resolving the actual verdict row_ids
    * and patching them before insertion.
    *
    * @param summariesWithVerdicts paired traffic summaries and verdicts (pre-joined by sequencing time)
    * @return annotated input: each pair with an optional computed activity record (verdictRowId = 0)
    */
  def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)]
  )(implicit tc: TraceContext): Future[Seq[
    (
        DbScanVerdictStore.TrafficSummaryT,
        v30.Verdict,
        Option[DbAppActivityRecordStore.AppActivityRecordT],
    )
  ]] = {
    val tagged = summariesWithVerdicts.map { case (summary, verdict) =>
      val isEligible =
        if (verdict.verdict != v30.VerdictResult.VERDICT_RESULT_ACCEPTED) false
        else if (summary.totalTrafficCost <= AppActivityComputation.MaxTrafficCostBytes) true
        else {
          // Note we skip the computation to avoid integer overflows.
          // This should never happen as Canton does not process 100MB transactions.
          logger.warn(
            s"Skipping app activity record at ${summary.sequencingTime}: " +
              s"totalTrafficCost ${summary.totalTrafficCost} exceeds maximum ${AppActivityComputation.MaxTrafficCostBytes}"
          )
          false
        }
      (summary, verdict, isEligible)
    }

    val eligibleTimes = tagged.collect { case (s, _, true) => s.sequencingTime }

    if (eligibleTimes.isEmpty) {
      Future.successful(summariesWithVerdicts.map { case (s, v) => (s, v, None) })
    } else {
      for {
        roundInfoByTime <- dataProvider.lookupActiveOpenMiningRounds(eligibleTimes)

        results <- Future.traverse(tagged) {
          case (summary, verdict, false) =>
            Future.successful((summary, verdict, None))
          case (summary, verdict, true) =>
            roundInfoByTime.get(summary.sequencingTime) match {
              case Some((roundNumber, roundOpensAt)) =>
                dataProvider.lookupFeaturedAppPartiesAsOf(roundOpensAt).map { providers =>
                  (
                    summary,
                    verdict,
                    computeForSingleVerdict(summary, verdict, roundNumber, providers),
                  )
                }
              case None =>
                // Skip activity record computation as we don't have the necessary round data ingested.
                // This can happen for freshly onboarded SVs, but is not expected to happen once the first activity record has been computed.
                Future.successful((summary, verdict, None))
            }
        }
      } yield results
    }
  }

  private def computeForSingleVerdict(
      summary: DbScanVerdictStore.TrafficSummaryT,
      verdict: v30.Verdict,
      roundNumber: Long,
      featuredAppProviders: Set[String],
  ): Option[DbAppActivityRecordStore.AppActivityRecordT] = {
    val envelopesWithFeaturedAppConfirmers =
      summary.envelopeTrafficSummarys.flatMap { envelope =>
        val envelopeConfirmers =
          computeEnvelopeConfirmers(envelope, verdict.getTransactionViews.views)
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
          verdictRowId = 0L,
          roundNumber = roundNumber,
          appProviderParties = aggregatedWeights.keys.toSeq,
          appActivityWeights = aggregatedWeights.values.toSeq,
        )
      )
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
