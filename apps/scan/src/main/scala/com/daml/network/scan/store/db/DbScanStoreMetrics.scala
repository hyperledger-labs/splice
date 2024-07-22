// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.store.db

import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.CacheMetrics
import com.daml.metrics.api.MetricQualification.Latency
import com.daml.network.environment.SpliceMetrics

class DbScanStoreMetrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "scan_store"

  val earliestAggregatedRound: Gauge[Long] =
    metricsFactory.gauge(
      MetricInfo(
        name = prefix :+ "earliest-aggregated-round",
        summary = "Earliest aggregated round",
        description = "The earliest aggregated round.",
        qualification = Latency,
      ),
      -1L,
    )(MetricsContext.Empty)

  val latestAggregatedRound: Gauge[Long] =
    metricsFactory.gauge(
      MetricInfo(
        name = prefix :+ "latest-aggregated-round",
        summary = "Latest aggregated round",
        description = "The latest aggregated round.",
        qualification = Latency,
      ),
      -1L,
    )(MetricsContext.Empty)

  val cache = new CacheMetrics(prefix :+ "cache", metricsFactory)

  override def close() = {
    try earliestAggregatedRound.close()
    finally latestAggregatedRound.close()
  }
}
