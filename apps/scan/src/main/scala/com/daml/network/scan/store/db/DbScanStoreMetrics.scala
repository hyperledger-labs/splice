// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.store.db

import com.daml.metrics.api.MetricDoc.MetricQualification.Latency
import com.daml.metrics.api.MetricHandle.Gauge
import com.daml.metrics.api.{MetricsContext, MetricDoc, MetricName}
import com.daml.network.environment.SpliceMetrics
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory
import com.daml.metrics.CacheMetrics

class DbScanStoreMetrics(metricsFactory: CantonLabeledMetricsFactory) extends AutoCloseable {

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "scan_store"

  @MetricDoc.Tag(
    summary = "Earliest aggregated round",
    description = "The earliest aggregated round.",
    qualification = Latency,
  )
  val earliestAggregatedRound: Gauge[Long] =
    metricsFactory.gauge(prefix :+ "earliest-aggregated-round", -1L)(MetricsContext.Empty)

  @MetricDoc.Tag(
    summary = "Latest aggregated round",
    description = "The latest aggregated round.",
    qualification = Latency,
  )
  val latestAggregatedRound: Gauge[Long] =
    metricsFactory.gauge(prefix :+ "latest-aggregated-round", -1L)(MetricsContext.Empty)

  val cache = new CacheMetrics(prefix :+ "cache", metricsFactory)

  override def close() = {
    try earliestAggregatedRound.close()
    finally latestAggregatedRound.close()
  }
}
