// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Traffic
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore.RewardComputationSummary

class RewardComputationMetrics(metricsFactory: LabeledMetricsFactory)(implicit
    metricsContext: MetricsContext
) extends AutoCloseable {
  private val prefix: MetricName =
    SpliceMetrics.MetricsPrefix :+ "scan" :+ "reward_computation"

  val activePartiesCount: Gauge[Long] = metricsFactory.gauge(
    MetricInfo(
      name = prefix :+ "active_parties_count",
      summary = "Number of parties with activity in the latest computed round",
      qualification = Traffic,
    ),
    0L,
  )(metricsContext)

  val activityRecordsCount: Gauge[Long] = metricsFactory.gauge(
    MetricInfo(
      name = prefix :+ "activity_records_count",
      summary = "Number of activity records in the latest computed round",
      qualification = Traffic,
    ),
    0L,
  )(metricsContext)

  val rewardedPartiesCount: Gauge[Long] = metricsFactory.gauge(
    MetricInfo(
      name = prefix :+ "rewarded_parties_count",
      summary = "Number of parties with rewards in the latest computed round",
      qualification = Traffic,
    ),
    0L,
  )(metricsContext)

  val batchesCreatedCount: Gauge[Long] = metricsFactory.gauge(
    MetricInfo(
      name = prefix :+ "batches_created_count",
      summary = "Number of reward batches created in the latest computed round",
      qualification = Traffic,
    ),
    0L,
  )(metricsContext)

  def record(summary: RewardComputationSummary): Unit = {
    activePartiesCount.updateValue(summary.activePartiesCount)
    activityRecordsCount.updateValue(summary.activityRecordsCount)
    rewardedPartiesCount.updateValue(summary.rewardedPartiesCount)
    batchesCreatedCount.updateValue(summary.batchesCreatedCount)
  }

  override def close(): Unit = {
    activePartiesCount.close()
    activityRecordsCount.close()
    rewardedPartiesCount.close()
    batchesCreatedCount.close()
  }
}
