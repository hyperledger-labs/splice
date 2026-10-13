// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.metrics

import com.daml.metrics.api.MetricHandle.Gauge.CloseableGauge
import com.daml.metrics.api.MetricHandle.{Histogram, LabeledMetricsFactory, Timer}
import com.daml.metrics.api.MetricQualification.{Latency, Saturation}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

import java.time.Duration

class TreasuryMetrics(
    owner: PartyId,
    metricsFactory: LabeledMetricsFactory,
    queueSize: () => Long,
) extends AutoCloseable {
  private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "wallet" :+ "treasury"

  private val metricsContext: MetricsContext =
    MetricsContext.Empty.withExtraLabels("owner" -> owner.toString)

  private val queueSizeGauge: CloseableGauge =
    metricsFactory.closeableGaugeWithSupplier[Long](
      MetricInfo(
        prefix :+ "queue-size",
        summary = "Treasury operation queue size",
        description = "The number of operations currently queued in the treasury service.",
        qualification = Saturation,
      ),
      queueSize,
    )(metricsContext)

  private val queueLatencyTimer: Timer =
    metricsFactory.timer(
      MetricInfo(
        prefix :+ "queue-latency",
        summary = "Treasury operation queueing latency",
        description =
          "The time an operation spent in the queue of the treasury service. Note: This is only time between enqueuing and dequeuing, it excludes actual request processing.",
        qualification = Latency,
      )
    )(metricsContext)

  def recordQueueLatency(latency: Duration): Unit =
    queueLatencyTimer.update(latency)(MetricsContext.Empty)

  val batchSize: Histogram = metricsFactory.histogram(
    MetricInfo(
      name = prefix :+ "batch-size",
      summary = "The size of batches processed by the treasury service",
      Saturation,
    )
  )

  override def close(): Unit = queueSizeGauge.close()
}
