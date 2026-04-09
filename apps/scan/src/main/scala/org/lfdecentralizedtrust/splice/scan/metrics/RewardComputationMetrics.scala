// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Counter, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Traffic
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore.RewardComputationSummary

class RewardComputationMetrics(metricsFactory: LabeledMetricsFactory)(implicit
    metricsContext: MetricsContext
) {
  private val prefix: MetricName =
    SpliceMetrics.MetricsPrefix :+ "scan" :+ "reward_computation"

  val activePartiesCount: Counter = metricsFactory.counter(
    MetricInfo(
      name = prefix :+ "active-parties-count",
      summary = "Cumulative number of parties with activity across computed rounds",
      qualification = Traffic,
    )
  )(metricsContext)

  val activityRecordsCount: Counter = metricsFactory.counter(
    MetricInfo(
      name = prefix :+ "activity-records-count",
      summary = "Cumulative number of activity records summarized across computed rounds",
      qualification = Traffic,
    )
  )(metricsContext)

  val rewardedPartiesCount: Counter = metricsFactory.counter(
    MetricInfo(
      name = prefix :+ "rewarded-parties-count",
      summary = "Cumulative number of parties with rewards across computed rounds",
      qualification = Traffic,
    )
  )(metricsContext)

  val batchesCreatedCount: Counter = metricsFactory.counter(
    MetricInfo(
      name = prefix :+ "batches-created-count",
      summary = "Cumulative number of reward batches created across computed rounds",
      qualification = Traffic,
    )
  )(metricsContext)

  def record(summary: RewardComputationSummary): Unit = {
    activePartiesCount.inc(summary.activePartiesCount)(metricsContext)
    activityRecordsCount.inc(summary.activityRecordsCount)(metricsContext)
    rewardedPartiesCount.inc(summary.rewardedPartiesCount)(metricsContext)
    batchesCreatedCount.inc(summary.batchesCreatedCount)(metricsContext)
  }
}
