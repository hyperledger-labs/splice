// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.metrics

import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Meter, Timer}
import com.daml.metrics.api.MetricQualification.{Latency, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class ScanConnectionMetrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {
  private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "validator" :+ "scan"

  val latencyPerConnection: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "per_connection_latency",
        summary = "Latency per scan connection query",
        qualification = Latency,
        labelsWithDescription = Map(
          "scan_connection" -> "The scan connection of the request",
          "request" -> "Type of request",
        ),
      )
    )(MetricsContext.Empty)

  val failuresPerConnection: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "per_connection_errors",
      summary = "Count of per connection errors",
      qualification = Traffic,
      labelsWithDescription = Map(
        "scan_connection" -> "The scan connection of the request",
        "request" -> "Type of request",
      ),
    )
  )(MetricsContext.Empty)

  val bftReadLatency: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "bft_read_latency",
        summary = "Total latency after BFT reads",
        qualification = Latency,
      )
    )(MetricsContext.Empty)

  val bftCallFailures: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "bft_errors",
      summary = "Count of BFT errors",
      qualification = Traffic,
    )
  )(MetricsContext.Empty)

  override def close(): Unit = ???
}
