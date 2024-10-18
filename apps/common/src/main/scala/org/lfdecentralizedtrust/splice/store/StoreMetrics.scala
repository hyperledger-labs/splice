// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Timer}
import com.daml.metrics.api.MetricQualification.{Latency, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class StoreMetrics(metricsFactory: LabeledMetricsFactory)(metricsContext: MetricsContext)
    extends AutoCloseable {

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

  val acsSize: Gauge[Long] =
    metricsFactory.gauge(
      MetricInfo(
        name = prefix :+ "acs-size",
        summary = "The number of active contracts in this store",
        Traffic,
        "The number of active contracts in this store. Note that this is only in the given store. The participant might have contracts we do not ingest.",
      ),
      0L,
    )(metricsContext)

  override def close(): Unit = {
    acsSize.close()
  }
}
