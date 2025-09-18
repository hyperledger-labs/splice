// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Meter}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricQualification.Traffic
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class ScanMediatorVerdictIngestionMetrics(metricsFactory: LabeledMetricsFactory)
    extends AutoCloseable {
  private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "scan" :+ "verdict_ingestion"

  type CantonTimestampMicros = Long

  val lastIngestedRecordTime: Gauge[CantonTimestampMicros] = metricsFactory.gauge(
    MetricInfo(
      name = prefix :+ "last_record_time_us",
      summary = "Latest ingested mediator verdict record_time",
      qualification = Traffic,
    ),
    initial = 0L,
  )(MetricsContext.Empty)

  val verdictCount: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "count",
      summary = "Total number of ingested mediator verdicts",
      qualification = Traffic,
    )
  )(MetricsContext.Empty)

  val errors: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "errors",
      summary = "Count of ingestion stream errors",
      qualification = Traffic,
    )
  )(MetricsContext.Empty)

  override def close(): Unit = {
    lastIngestedRecordTime.close()
  }
}
