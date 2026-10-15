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
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppKind
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.environment.{DarResources, PackageIdResolver}
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

  private def floorMultiply(value: Long, weights: BigDecimal*): Long =
    weights
      .foldLeft(damlRight(Numeric.fromLong(scale, value))) { (acc, weight) =>
        damlRight(Numeric.multiply(scale, acc, Numeric.assertFromBigDecimal(scale, weight)))
      }
      .setScale(0, RoundingMode.FLOOR)
      .longValueExact()

  // Whether to perform the CIP-104 amendement based calcs for FA submitted transactions
  // This guard exists to synchronize the time the amendement in calcs becomes active among SVs
  def computeFASubmitted(amuletConfig: Option[AmuletConfig[USD]]): Boolean =
    amuletConfig.exists { config =>
      PackageIdResolver.readPackageVersion(
        config.packageConfig,
        PackageIdResolver.Package.SpliceAmulet,
      ) > DarResources.amulet_0_1_24.metadata.version
    }

  /** The featured app provider that submitted a transaction, if any.
    *
    * A transaction is submitted by the featured app X if
    * - X is a confirmer of the transaction,
    * - X is a featured app, and
    * - X or one of X's ops parties is included in `submitters`.
    *
    * Ties are broken by picking the app whose submitter appears earliest in `submitters`.
    * A submitter that is a provider maps to itself, even if it is also an ops party of another provider.
    * The tie-break on the lexicographically smallest app is already applied when building
    * `opsPartyProviders`, as each ops party maps to a single provider,
    * see [[org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore.lookupFeaturedAppPartiesAsOf]].
    *
    * @param submitters the submitting parties of the transaction, in order
    * @param confirmers all confirming parties of the transaction
    * @param featuredAppParties as returned by `lookupFeaturedAppPartiesAsOf` at the start of the round
    */
  def findFASubmitter(
      submitters: Seq[String],
      confirmers: Set[String],
      featuredAppParties: ScanRewardsReferenceStore.FeaturedAppParties,
  ): Option[String] =
    submitters.iterator
      .flatMap { submitter =>
        if (featuredAppParties.providers.contains(submitter)) Some(submitter)
        else featuredAppParties.opsPartyProviders.get(submitter)
      }
      .find(confirmers.contains)
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

  /** The OpenMiningRound round number active at asOf, if the round data has been ingested. */
  def lookupActiveOpenMiningRound(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[Long]] =
    rewardsReferenceStore
      .lookupActiveOpenMiningRounds(Seq(asOf))
      .map(_.get(asOf).map(_.roundNumber))

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
            (
              summary,
              verdict,
              None,
              roundInfoByTime.get(summary.sequencingTime).map(_.roundNumber),
            )
          )
        case (summary, verdict, true) =>
          roundInfoByTime.get(summary.sequencingTime) match {
            case Some(
                  ScanRewardsReferenceStore.ActiveRoundInfo(
                    roundNumber,
                    roundOpensAt,
                    assetActivityParams,
                  )
                ) =>
              for {
                featuredAppParties <- rewardsReferenceStore.lookupFeaturedAppPartiesAsOf(
                  roundOpensAt
                )
                svParticipantIds <- rewardsReferenceStore.lookupSvParticipantIdsAsOf(
                  roundOpensAt
                )
                amuletConfig <- rewardsReferenceStore.lookupAmuletConfigAsOf(roundOpensAt)
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
                    computeForSingleVerdict(
                      summary,
                      verdict,
                      roundNumber,
                      featuredAppParties,
                      assetActivityParams,
                      AppActivityComputation.computeFASubmitted(amuletConfig),
                    ),
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
      featuredAppParties: ScanRewardsReferenceStore.FeaturedAppParties,
      assetActivityParams: Option[AssetActivityParams],
      computeFASubmitted: Boolean,
  ): Option[DbAppActivityRecordStore.AppActivityRecordT] = {
    val confirmers = verdict.getTransactionViews.views.values
      .flatMap(_.confirmingParties.flatMap(_.parties))
      .toSet
    val faSubmitter =
      if (computeFASubmitted)
        AppActivityComputation.findFASubmitter(
          verdict.submittingParties,
          confirmers,
          featuredAppParties,
        )
      else None
    faSubmitter match {
      case Some(submittingApp) =>
        Some(
          computeForFASubmitted(
            summary,
            verdict,
            roundNumber,
            submittingApp,
            featuredAppParties.providers,
            rewardsReferenceStore.key.dsoParty.toProtoPrimitive,
            assetActivityParams,
          )
        )
      case None =>
        computeForUserSubmitted(summary, verdict, roundNumber, featuredAppParties.providers)
    }
  }

  private[rewards] def computeForUserSubmitted(
      summary: DbScanVerdictStore.TrafficSummaryT,
      verdict: v30.Verdict,
      roundNumber: Long,
      featuredAppProviders: Map[String, (FeaturedAppKind, BigDecimal)],
  ): Option[DbAppActivityRecordStore.AppActivityRecordT] = {
    val envelopesWithFeaturedAppConfirmers =
      summary.envelopeTrafficSummarys.flatMap { envelope =>
        val envelopeConfirmers =
          computeEnvelopeConfirmers(envelope, verdict.getTransactionViews.views)
        val featuredAppConfirmers = envelopeConfirmers.filter(featuredAppProviders.contains)
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

      Some(
        DbAppActivityRecordStore.AppActivityRecordT(
          verdictRowId = DbAppActivityRecordStore.DUMMY_VERDICT_ROW_ID,
          roundNumber = roundNumber,
          appProviderParties = aggregatedBurn.keys.toSeq,
          appActivityWeights = aggregatedBurn.values.toSeq,
        )
      )
    }
  }

  /** Compute the activity records of a transaction submitted by the featured app `submittingApp`,
    * see [[AppActivityComputation.findFASubmitter]].
    *
    * The total burn is always attributed to the submitting app as app-activity.
    *
    * Additionally if the submitting app is a featured asset mover and the
    * round's reward config sets the asset activity params, then the extra
    * burn is split among the asset confirmers as described in the
    * "Splitting Extra Burn Among Assets" section of CIP-104.
    * The asset confirmers are the dso party and the providers of featured assets.
    */
  private[rewards] def computeForFASubmitted(
      summary: DbScanVerdictStore.TrafficSummaryT,
      verdict: v30.Verdict,
      roundNumber: Long,
      submittingApp: String,
      featuredAppProviders: Map[String, (FeaturedAppKind, BigDecimal)],
      dsoParty: String,
      paramsO: Option[AssetActivityParams],
  ): DbAppActivityRecordStore.AppActivityRecordT = {
    val assetActivity = (featuredAppProviders.get(submittingApp), paramsO) match {
      case (
            Some((FeaturedAppKind.FAK_ASSETMOVING, submittingAppWeight)),
            Some(params),
          ) =>
        computeAssetActivity(
          summary,
          verdict,
          submittingApp,
          submittingAppWeight,
          featuredAppProviders,
          dsoParty,
          params,
        ).getOrElse(SortedMap.empty[String, Long])
      case _ => SortedMap.empty[String, Long]
    }

    DbAppActivityRecordStore.AppActivityRecordT(
      verdictRowId = DbAppActivityRecordStore.DUMMY_VERDICT_ROW_ID,
      roundNumber = roundNumber,
      appProviderParties = Seq(submittingApp),
      appActivityWeights = Seq(summary.totalTrafficCost),
      submittingAppProvider = Some(submittingApp),
      assetActivityProviderParties = assetActivity.keys.toSeq,
      assetActivityWeights = assetActivity.values.toSeq,
    )
  }

  private def computeAssetActivity(
      summary: DbScanVerdictStore.TrafficSummaryT,
      verdict: v30.Verdict,
      submittingApp: String,
      submittingAppWeight: BigDecimal,
      featuredAppProviders: Map[String, (FeaturedAppKind, BigDecimal)],
      dsoParty: String,
      params: AssetActivityParams,
  ): Option[SortedMap[String, Long]] = {
    val totalBurn = summary.totalTrafficCost

    // Step 1: the asset confirmers of each envelope, with their weights
    val envelopesWithAssetConfirmers =
      summary.envelopeTrafficSummarys.flatMap { envelope =>
        val envelopeConfirmers =
          computeEnvelopeConfirmers(envelope, verdict.getTransactionViews.views)
        val assetConfirmers: Map[String, BigDecimal] = envelopeConfirmers.flatMap { party =>
          if (party == dsoParty) Some(dsoParty -> params.dsoAppActivityWeight)
          else
            featuredAppProviders.get(party).collect { case (FeaturedAppKind.FAK_ASSET, weight) =>
              party -> weight
            }
        }.toMap
        Option.when(assetConfirmers.nonEmpty)((envelope, assetConfirmers))
      }
    val assetConfirmerWeights: Map[String, BigDecimal] =
      envelopesWithAssetConfirmers.flatMap(_._2).toMap

    // Step 3: the sum of assetEnvelopeBurn over all asset confirmers is the
    // total traffic of the envelopes with asset confirmers
    val totalAssetBurn: Long = envelopesWithAssetConfirmers.map(_._1.trafficCost).sum

    if (totalAssetBurn == 0L) None
    else {
      // Steps 2 and 4
      // Distribute activity for each envelope by doing the sum first, then divide by totalAssetBurn
      val assetActivityUncapped = computeAggregatedBurn(
        envelopesWithAssetConfirmers.map { case (envelope, confirmers) =>
          (envelope, confirmers.keySet)
        },
        AppActivityComputation.floorMultiply(totalBurn, params.extraFeaturedAssetActivityWeight),
        totalAssetBurn,
      )

      // Steps 5 and 6
      val assetActivityCap = AppActivityComputation.floorMultiply(
        totalBurn,
        params.maxExtraFeaturedAssetActivityWeightPerAsset,
      )
      val assetActivityCapped = assetActivityUncapped.view.mapValues(_ min assetActivityCap)

      // Step 7: redistribute the capped activity once among the uncapped assets
      val totalCappedActivity =
        assetActivityUncapped.values.map(_ - assetActivityCap).filter(_ > 0L).sum
      val uncappedAssets = assetActivityUncapped.filter { case (_, a) => a < assetActivityCap }
      val totalUncappedActivity = uncappedAssets.values.sum
      val assetActivity =
        if (totalUncappedActivity == 0L) SortedMap.from(assetActivityCapped)
        else
          SortedMap.from(assetActivityCapped) ++ uncappedAssets.map {
            case (party, uncappedActivity) =>
              val cappedActivityShare =
                (totalCappedActivity * uncappedActivity) / totalUncappedActivity
              party -> ((uncappedActivity + cappedActivityShare) min assetActivityCap)
          }

      // Steps 8 and 9: apply the app composition weights, and attribute the
      // dso's asset activity to the submitting app.
      // Because the submitting app is not an FAK_ASSET, the beneficiaries are guaranteed to be distinct.
      Some(assetActivity.map { case (party, activity) =>
        val weight = assetConfirmerWeights.getOrElse(
          party,
          throw new IllegalStateException(s"No asset weight found for party=$party"),
        )
        val beneficiary = if (party == dsoParty) submittingApp else party
        beneficiary -> AppActivityComputation.floorMultiply(activity, submittingAppWeight, weight)
      })
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
