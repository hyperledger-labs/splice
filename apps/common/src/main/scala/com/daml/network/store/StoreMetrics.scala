// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

import com.daml.metrics.api.{MetricInfo, MetricName}
import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Timer}
import com.daml.metrics.api.MetricQualification.Latency
import com.daml.network.environment.SpliceMetrics

class StoreMetrics(metricsFactory: LabeledMetricsFactory) {

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "store"

  val signalWhenIngestedLatency: Timer =
    metricsFactory.timer(
      MetricInfo(
        prefix :+ "signal-when-ingested-latency",
        "How long it takes to signal offset ingestion.",
        Latency,
        "This metric measures the time taken for the future returned by `signalWhenIngestedOrShutdown` to complete as an indicication for how far our transaction ingestion lags behind ledger end.",
      )
    )

}
