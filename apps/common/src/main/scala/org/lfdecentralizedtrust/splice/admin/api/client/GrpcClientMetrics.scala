// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api.client

import com.daml.metrics.api.{MetricHandle, MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.{Histogram, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.{Latency, Traffic}
import org.lfdecentralizedtrust.splice.admin.api.client.DamlGrpcClientMetrics.GrpcClientMetricsPrefix

object DamlGrpcClientMetrics {
  val GrpcClientMetricsPrefix = MetricName.Daml :+ "grpc" :+ "client"
}

class DamlGrpcClientMetrics(
    metricsFactory: LabeledMetricsFactory,
    component: String,
) extends GrpcClientMetrics {

  private implicit val metricsContext: MetricsContext = MetricsContext(
    Map("service" -> component)
  )

  override val callTimer: MetricHandle.Timer = metricsFactory.timer(
    MetricInfo(
      GrpcClientMetricsPrefix,
      "Distribution of the durations of serving gRPC requests.",
      Latency,
    )
  )
  override val messagesSent: MetricHandle.Meter = metricsFactory.meter(
    MetricInfo(
      GrpcClientMetricsPrefix :+ "messages" :+ "sent",
      "Total number of gRPC messages sent (on either type of connection).",
      Traffic,
    )
  )
  override val messagesReceived: MetricHandle.Meter = metricsFactory.meter(
    MetricInfo(
      GrpcClientMetricsPrefix :+ "messages" :+ "received",
      "Total number of gRPC messages received (on either type of connection).",
      Traffic,
    )
  )
  override val messagesSentSize: MetricHandle.Histogram = metricsFactory.histogram(
    MetricInfo(
      GrpcClientMetricsPrefix :+ "messages" :+ "sent" :+ Histogram.Bytes,
      "Distribution of payload sizes in gRPC messages sent (both unary and streaming).",
      Traffic,
    )
  )
  override val messagesReceivedSize: MetricHandle.Histogram = metricsFactory.histogram(
    MetricInfo(
      GrpcClientMetricsPrefix :+ "messages" :+ "received" :+ Histogram.Bytes,
      "Distribution of payload sizes in gRPC messages received (both unary and streaming).",
      Traffic,
    )
  )
  override val callsStarted: MetricHandle.Meter = metricsFactory.meter(
    MetricInfo(
      GrpcClientMetricsPrefix :+ "started",
      "Total number of started gRPC requests (on either type of connection).",
      Traffic,
    )
  )
  override val callsCompleted: MetricHandle.Meter = metricsFactory.meter(
    MetricInfo(
      GrpcClientMetricsPrefix :+ "completed",
      "Total number of completed (not necessarily successful) gRPC requests.",
      Traffic,
    )
  )

}
