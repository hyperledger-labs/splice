// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Meter}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricQualification.Traffic
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.automation.StreamIngestionService
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

/** Parameterized metrics class for stream-based ingestion services.
  *
  * @param metricsFactory Factory for creating metrics
  * @param prefixComponent The component name for the metric prefix (e.g., "verdict_ingestion", "traffic_ingestion")
  * @param timestampMetricName Name for the timestamp gauge (e.g., "last_record_time_us", "last_sequencing_time_us")
  * @param timestampSummary Summary text for the timestamp gauge
  * @param countSummary Summary text for the count meter
  */
class StreamIngestionMetrics(
    metricsFactory: LabeledMetricsFactory,
    prefixComponent: String,
    timestampMetricName: String,
    timestampSummary: String,
    countSummary: String,
) extends StreamIngestionService.IngestionMetrics
    with AutoCloseable {
  private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "scan" :+ prefixComponent

  val lastIngestedTimestamp: Gauge[CantonTimestamp] = SpliceMetrics.cantonTimestampGauge(
    metricsFactory,
    MetricInfo(
      name = prefix :+ timestampMetricName,
      summary = timestampSummary,
      qualification = Traffic,
    ),
    initial = CantonTimestamp.MinValue,
  )(MetricsContext.Empty)

  val count: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "count",
      summary = countSummary,
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
    lastIngestedTimestamp.close()
  }
}

object StreamIngestionMetrics {

  def forVerdictIngestion(metricsFactory: LabeledMetricsFactory): StreamIngestionMetrics =
    new StreamIngestionMetrics(
      metricsFactory,
      prefixComponent = "verdict_ingestion",
      timestampMetricName = "last_record_time_us",
      timestampSummary = "Latest ingested mediator verdict record_time",
      countSummary = "Total number of ingested mediator verdicts",
    )

  def forTrafficIngestion(metricsFactory: LabeledMetricsFactory): StreamIngestionMetrics =
    new StreamIngestionMetrics(
      metricsFactory,
      prefixComponent = "traffic_ingestion",
      timestampMetricName = "last_sequencing_time_us",
      timestampSummary = "Latest ingested sequencer traffic summary sequencing time",
      countSummary = "Total number of ingested sequencer traffic summaries",
    )
}
