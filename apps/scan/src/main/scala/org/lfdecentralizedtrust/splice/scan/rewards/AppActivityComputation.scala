// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import io.grpc.Status
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Numeric
import com.digitalasset.daml.lf.data.assertRight as damlRight
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}

import java.math.RoundingMode
import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}

/** Computation of app activity records, ie per featured app traffic weight for a single verdict
  * See CIP-104 for details.
  */
object AppActivityComputation {
  val MaxTrafficCostBytes: Long = 100L * 1024 * 1024 // 100 MB

  private val scale: Numeric.Scale = Numeric.Scale.assertFromInt(10)

  /** Bump this when the functional behavior of the app activity computation changes.
    * WARNING: this MUST be bumped with utmost care to avoid the loss of
    * actual activity records due to all SV nodes upgrading and resetting
    * their stores at the same time, which in turn also breaks the BFT-read
    * fallback as there is no node to fallback to.
    */
  val ActivityIngestionCodeVersion: Int = 1
}

class AppActivityComputation(
    rewardsReferenceStore: ScanRewardsReferenceStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def waitUntilInitialized: Future[Unit] = rewardsReferenceStore.waitUntilInitialized

  /** The highest OpenMiningRound round number archived at or before asOf.
    * See [[org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore.lookupLatestArchivedOpenMiningRound]].
    */
  def lookupLatestArchivedOpenMiningRound(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[Long]] =
    rewardsReferenceStore.lookupLatestArchivedOpenMiningRound(asOf)

  /** Compute app activity records for a batch of verdicts.
    *
    * Records are returned with verdictRowId = DUMMY_VERDICT_ROW_ID as a placeholder.
    * The caller is responsible for resolving the actual verdict row_ids
    * and patching them before insertion.
    *
    * @param summariesWithVerdicts paired traffic summaries and verdicts (pre-joined by sequencing time)
    * @return annotated input: each pair with an optional computed activity record (verdictRowId = DUMMY_VERDICT_ROW_ID)
    */
  def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)]
  )(implicit tc: TraceContext): Future[Seq[
    (
        DbScanVerdictStore.TrafficSummaryT,
        v30.Verdict,
        Option[DbAppActivityRecordStore.AppActivityRecordT],
        Option[Long],
    )
  ]] = {
    val allTimes = summariesWithVerdicts.collect { case (s, _) => s.sequencingTime }
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

    for {
      roundInfoByTime <- rewardsReferenceStore.lookupActiveOpenMiningRounds(allTimes)
      results <- Future.traverse(tagged) {
        case (summary, verdict, false) =>
          Future.successful(
            (summary, verdict, None, roundInfoByTime.get(summary.sequencingTime).map(_._1))
          )
        case (summary, verdict, true) =>
          roundInfoByTime.get(summary.sequencingTime) match {
            case Some((roundNumber, roundOpensAt)) =>
              for {
                featuredAppWeights <- rewardsReferenceStore.lookupFeaturedAppPartiesAsOf(
                  roundOpensAt
                )
                svParticipantIds <- rewardsReferenceStore.lookupSvParticipantIdsAsOf(
                  roundOpensAt
                )
              } yield {
                if (svParticipantIds.isEmpty) {
                  // We should never hit this; as we have round info in store and must have DsoRules also
                  throw Status.INTERNAL
                    .withDescription(
                      s"Round info present as of roundOpensAt=$roundOpensAt, " +
                        s"but no DsoRules/Sv participant found"
                    )
                    .asRuntimeException()
                } else if (svParticipantIds.contains(verdict.submittingParticipantUid)) {
                  // SV-submitted transactions don't burn traffic, so they
                  // must not contribute to app activity.
                  (summary, verdict, None, Some(roundNumber))
                } else {
                  (
                    summary,
                    verdict,
                    computeForSingleVerdict(summary, verdict, roundNumber, featuredAppWeights),
                    Some(roundNumber),
                  )
                }
              }
            case None =>
              // Skip activity record computation as we don't have the necessary round data ingested.
              // This can happen for freshly onboarded SVs if the reward
              // reference store does not have the data for any of the
              // sequencingTime(s) in this batch.
              // OTOH this cannot happen after app activity record ingestion started because
              // lookupActiveOpenMiningRounds blocks until the reference store
              // has caught up to all the sequencingTime(s) in this batch.
              logger.debug(
                s"No round data found for sequencingTime=${summary.sequencingTime}, skipping activity record computation"
              )
              Future.successful((summary, verdict, None, None))
          }
      }
    } yield results
  }

  private def computeForSingleVerdict(
      summary: DbScanVerdictStore.TrafficSummaryT,
      verdict: v30.Verdict,
      roundNumber: Long,
      featuredAppWeights: Map[String, BigDecimal],
  ): Option[DbAppActivityRecordStore.AppActivityRecordT] = {
    val envelopesWithFeaturedAppConfirmers =
      summary.envelopeTrafficSummarys.flatMap { envelope =>
        val envelopeConfirmers =
          computeEnvelopeConfirmers(envelope, verdict.getTransactionViews.views)
        val featuredAppConfirmers = envelopeConfirmers.intersect(featuredAppWeights.keySet)
        Option.when(featuredAppConfirmers.nonEmpty)((envelope, featuredAppConfirmers))
      }

    val totalFeaturedAppEnvelopesTraffic: Long =
      envelopesWithFeaturedAppConfirmers.map(_._1.trafficCost).sum

    if (totalFeaturedAppEnvelopesTraffic == 0L) None
    else {
      val aggregatedBurn = computeAggregatedBurn(
        envelopesWithFeaturedAppConfirmers,
        summary.totalTrafficCost,
        totalFeaturedAppEnvelopesTraffic,
      )

      val aggregatedWeights = SortedMap.from(aggregatedBurn.map { case (party, burn) =>
        val weight =
          featuredAppWeights.getOrElse(
            party,
            throw new IllegalStateException(
              s"No featured app weight found for party=$party"
            ),
          )
        val weightNumeric = Numeric.assertFromBigDecimal(AppActivityComputation.scale, weight)
        val burnNumeric = damlRight(Numeric.fromLong(AppActivityComputation.scale, burn))
        val product =
          damlRight(Numeric.multiply(AppActivityComputation.scale, burnNumeric, weightNumeric))
        party -> product.setScale(0, RoundingMode.FLOOR).longValueExact()
      })

      Some(
        DbAppActivityRecordStore.AppActivityRecordT(
          verdictRowId = DbAppActivityRecordStore.DUMMY_VERDICT_ROW_ID,
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
  private def computeAggregatedBurn(
      envelopesWithFeaturedAppConfirmers: Seq[(DbScanVerdictStore.EnvelopeT, Set[String])],
      totalConfirmationRequestTraffic: Long,
      totalFeaturedAppEnvelopesTraffic: Long,
  ): SortedMap[String, Long] = {
    val perPartyNumerator = envelopesWithFeaturedAppConfirmers
      .foldLeft(SortedMap.empty[String, Long]) { case (acc, (envelope, featuredAppConfirmers)) =>
        // The computations are performed using integer arithmetic for efficiency and determinism reasons.
        // see CIP-104 for details.
        val perEnvelopeNumerator: Long =
          (envelope.trafficCost * totalConfirmationRequestTraffic) / featuredAppConfirmers.size.toLong
        featuredAppConfirmers.foldLeft(acc) { (innerAcc, party) =>
          innerAcc.updatedWith(party)(prev => Some(prev.getOrElse(0L) + perEnvelopeNumerator))
        }
      }

    SortedMap.from(perPartyNumerator.view.mapValues(_ / totalFeaturedAppEnvelopesTraffic))
  }
}
