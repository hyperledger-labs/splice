// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Saturation
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.Mutex
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.store.HardLimit
import org.lfdecentralizedtrust.splice.sv.automation.RewardMetricsTrigger.RewardMetrics
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

class RewardMetricsTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    hiddenCouponScanLimit: Int = 10_000,
    hiddenCouponMaxProviders: Int = 50,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    val mat: Materializer,
) extends PollingTrigger {

  private val rewardMetrics = new RewardMetrics(context.metricsFactory)

  // TODO(#111): (placeholder issue #, replace with real follow-up) partition
  // these gauges by kind; validator-kind contracts currently roll up into
  // the app-flow gauges below.
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      // These listings are capped at the default page size (1000), so the dryRun/minting counts
      // may underestimate if there are more than that. That's acceptable: we log a warning when
      // the cap is hit, and an alert already fires when either count is above 1.
      calculateRewards <- dsoStore.listCalculateRewardsV2()
      _ = {
        val (dryRun, minting) = calculateRewards.partition(_.payload.dryRun)
        rewardMetrics.calculateRewardsActiveContractsDryRun.updateValue(dryRun.size)
        rewardMetrics.calculateRewardsActiveContractsMinting.updateValue(minting.size)
      }
      processRewards <- dsoStore.listProcessRewardsV2()
      _ = {
        val (dryRun, minting) = processRewards.partition(_.payload.dryRun)
        rewardMetrics.processRewardsActiveContractsDryRun.updateValue(dryRun.size)
        rewardMetrics.processRewardsActiveContractsMinting.updateValue(minting.size)
      }
      // Gauge for the providers with the most hidden coupons.
      providerCoupons <- dsoStore.listTopNonObserverRewardCouponV2Providers(
        couponScanLimit = hiddenCouponScanLimit,
        maxProviders = hiddenCouponMaxProviders,
      )
      _ = {
        providerCoupons.foreach { case (provider, count) =>
          rewardMetrics.updateHiddenCoupons(provider, count)
        }
        rewardMetrics.pruneHiddenCouponGauges(providerCoupons.map(_._1))
      }
      rewardCouponCounts <- dsoStore.getRewardCouponsV2AgeHistogram(
        rewardMetrics.rewardCouponsActiveContractsBucket1.ageUpperBound,
        rewardMetrics.rewardCouponsActiveContractsBucket2.ageUpperBound,
        rewardMetrics.rewardCouponsActiveContractsBucket3.ageUpperBound,
        context.clock.now,
        // Limit the sequential scan to 100k rows,
        // which is a reasonable upper bound for the number of active reward coupons.
        HardLimit.tryCreate(100000, 100000),
      )
      _ = {
        rewardMetrics.rewardCouponsActiveContractsBucket1.gauge.updateValue(
          rewardCouponCounts._1
        )
        rewardMetrics.rewardCouponsActiveContractsBucket2.gauge.updateValue(
          rewardCouponCounts._2
        )
        rewardMetrics.rewardCouponsActiveContractsBucket3.gauge.updateValue(
          rewardCouponCounts._3
        )
        rewardMetrics.rewardCouponsActiveContractsBucket4.gauge.updateValue(
          rewardCouponCounts._4
        )
      }
    } yield false

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync().appended(SyncCloseable("reward metrics", rewardMetrics.close()))
}

object RewardMetricsTrigger {

  class RewardMetrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {

    private val prefix: MetricName = SpliceMetrics.MetricsPrefix

    private def activeContractsGauge(
        template: String,
        templateName: String,
        dryRun: Boolean,
    ): Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = prefix :+ template :+ "active_contracts",
          summary =
            s"The number of active $templateName contracts, as seen by the RewardMetricsTrigger",
          qualification = Saturation,
        ),
        initial = -1,
      )(MetricsContext.Empty.withExtraLabels("dryRun" -> dryRun.toString))

    val calculateRewardsActiveContractsDryRun: Gauge[Int] =
      activeContractsGauge("calculate_rewards_v2", "CalculateRewardsV2", dryRun = true)
    val calculateRewardsActiveContractsMinting: Gauge[Int] =
      activeContractsGauge("calculate_rewards_v2", "CalculateRewardsV2", dryRun = false)
    val processRewardsActiveContractsDryRun: Gauge[Int] =
      activeContractsGauge("process_rewards_v2", "ProcessRewardsV2", dryRun = true)
    val processRewardsActiveContractsMinting: Gauge[Int] =
      activeContractsGauge("process_rewards_v2", "ProcessRewardsV2", dryRun = false)

    case class HistogramBucketGauge(
        bucket: Int,
        gauge: Gauge[Long],
        ageLowerBound: FiniteDuration,
        ageUpperBound: FiniteDuration,
    )
    private def activeRewardCouponsAgeHistogramGauge(
        bucket: Int,
        ageLowerBound: FiniteDuration,
        ageUpperBound: FiniteDuration,
    ): HistogramBucketGauge = {
      val gauge = metricsFactory.gauge[Long](
        MetricInfo(
          name = prefix :+ "reward_coupon_v2" :+ "active_contracts",
          summary =
            s"The number of active RewardCouponV2 contracts (bucketed by contract age), as seen by the RewardMetricsTrigger",
          qualification = Saturation,
        ),
        initial = -1L,
      )(
        MetricsContext.Empty.withExtraLabels(
          "bucket" -> bucket.toString,
          "ageLowerBound" -> ageLowerBound.toString,
          "ageUpperBound" -> ageUpperBound.toString,
        )
      )
      HistogramBucketGauge(bucket, gauge, ageLowerBound, ageUpperBound)
    }

    object RewardCouponBucketThresholds {
      val t0: FiniteDuration = 0.seconds
      val t1: FiniteDuration = 10.minutes
      val t2: FiniteDuration = 1.hours
      val t3: FiniteDuration = 12.hours
      val t4: FiniteDuration = 9999.days // not an actual bound, only used for the metric label
    }
    val rewardCouponsActiveContractsBucket1: HistogramBucketGauge =
      activeRewardCouponsAgeHistogramGauge(
        0,
        RewardCouponBucketThresholds.t0,
        RewardCouponBucketThresholds.t1,
      )
    val rewardCouponsActiveContractsBucket2: HistogramBucketGauge =
      activeRewardCouponsAgeHistogramGauge(
        1,
        RewardCouponBucketThresholds.t1,
        RewardCouponBucketThresholds.t2,
      )
    val rewardCouponsActiveContractsBucket3: HistogramBucketGauge =
      activeRewardCouponsAgeHistogramGauge(
        2,
        RewardCouponBucketThresholds.t2,
        RewardCouponBucketThresholds.t3,
      )
    val rewardCouponsActiveContractsBucket4: HistogramBucketGauge =
      activeRewardCouponsAgeHistogramGauge(
        3,
        RewardCouponBucketThresholds.t3,
        RewardCouponBucketThresholds.t4,
      )

    private val hiddenCouponGauges: mutable.Map[PartyId, Gauge[Long]] = mutable.Map.empty
    private val lock = new Mutex()

    private def getHiddenCouponGauge(provider: PartyId): Gauge[Long] =
      lock.exclusive {
        hiddenCouponGauges.getOrElseUpdate(
          provider,
          metricsFactory.gauge(
            MetricInfo(
              prefix :+ "reward_coupons_v2" :+ "hidden_coupons",
              "The number of hidden (non-observer) RewardCouponV2 contracts for this provider party",
              Saturation,
            ),
            initial = 0L,
          )(MetricsContext.Empty.withExtraLabels("party" -> provider.toProtoPrimitive)),
        )
      }

    def updateHiddenCoupons(provider: PartyId, count: Long): Unit =
      getHiddenCouponGauge(provider).updateValue(count)

    def pruneHiddenCouponGauges(currentProviders: Seq[PartyId]): Unit = {
      val keep = currentProviders.toSet
      lock.exclusive {
        val toClose = hiddenCouponGauges.keySet.filterNot(keep.contains).toSeq
        toClose.foreach(provider => hiddenCouponGauges.remove(provider).foreach(_.close()))
      }
    }

    override def close(): Unit = {
      calculateRewardsActiveContractsDryRun.close()
      calculateRewardsActiveContractsMinting.close()
      processRewardsActiveContractsDryRun.close()
      processRewardsActiveContractsMinting.close()
      lock.exclusive {
        hiddenCouponGauges.values.foreach(_.close())
      }
      rewardCouponsActiveContractsBucket1.gauge.close()
      rewardCouponsActiveContractsBucket2.gauge.close()
      rewardCouponsActiveContractsBucket3.gauge.close()
      rewardCouponsActiveContractsBucket4.gauge.close()
    }
  }
}
