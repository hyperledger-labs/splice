// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, Histogram, LabeledMetricsFactory, Meter, Timer}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricQualification.{Latency, Saturation, Traffic}
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class ScanMediatorVerdictIngestionMetrics(metricsFactory: LabeledMetricsFactory)
    extends AutoCloseable {
  private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "scan" :+ "verdict_ingestion"

  val lastIngestedRecordTime: Gauge[CantonTimestamp] = SpliceMetrics.cantonTimestampGauge(
    metricsFactory,
    MetricInfo(
      name = prefix :+ "last_record_time_us",
      summary = "Latest ingested mediator verdict record_time",
      qualification = Traffic,
    ),
    initial = CantonTimestamp.MinValue,
  )(MetricsContext.Empty)

  val verdictCount: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "count",
      summary = "Total number of ingested mediator verdicts",
      qualification = Traffic,
    )
  )(MetricsContext.Empty)

  val latency: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "latency",
        summary = "How long it takes to ingest a batch of verdicts",
        qualification = Latency,
      )
    )(MetricsContext.Empty)

  val batchSize: Histogram =
    metricsFactory.histogram(
      MetricInfo(
        name = prefix :+ "batch-size",
        summary = "Number of verdicts ingested in a batch",
        qualification = Saturation,
      )
    )(MetricsContext.Empty)

  override def close(): Unit = {
    lastIngestedRecordTime.close()
  }
}
