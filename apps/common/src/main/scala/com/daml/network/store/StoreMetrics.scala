// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

import com.daml.metrics.api.MetricDoc.MetricQualification.Latency
import com.daml.metrics.api.MetricHandle.Timer
import com.daml.metrics.api.{MetricDoc, MetricName}
import com.daml.network.environment.SpliceMetrics
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory

class StoreMetrics(metricsFactory: CantonLabeledMetricsFactory) {

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "store"

  @MetricDoc.Tag(
    summary = "How long it takes to signal offset ingestion.",
    description =
      "This metric measures the time taken for the future returned by `signalWhenIngestedOrShutdown` to complete as an indicication for how far our transaction ingestion lags behind ledger end.",
    qualification = Latency,
  )
  val signalWhenIngestedLatency: Timer =
    metricsFactory.timer(prefix :+ "signal-when-ingested-latency")

}
