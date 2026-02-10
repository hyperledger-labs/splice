// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Meter}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricQualification.Traffic
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class ScanSequencerTrafficIngestionMetrics(metricsFactory: LabeledMetricsFactory)
    extends AutoCloseable {
  private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "scan" :+ "traffic_ingestion"

  val lastIngestedSequencingTime: Gauge[CantonTimestamp] = SpliceMetrics.cantonTimestampGauge(
    metricsFactory,
    MetricInfo(
      name = prefix :+ "last_sequencing_time_us",
      summary = "Latest ingested sequencer traffic summary sequencing time",
      qualification = Traffic,
    ),
    initial = CantonTimestamp.MinValue,
  )(MetricsContext.Empty)

  val summaryCount: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "count",
      summary = "Total number of ingested sequencer traffic summaries",
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

  val restartErrors: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "restart_errors",
      summary = "Count of ingestion restart errors",
      qualification = Traffic,
    )
  )(MetricsContext.Empty)

  override def close(): Unit = {
    lastIngestedSequencingTime.close()
  }
}
