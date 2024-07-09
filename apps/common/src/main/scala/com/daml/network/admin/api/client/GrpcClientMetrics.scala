// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.admin.api.client

import com.daml.metrics.api.MetricHandle.{Histogram, LabeledMetricsFactory}
import com.daml.metrics.api.{MetricHandle, MetricName, MetricsContext}
import com.daml.network.admin.api.client.DamlGrpcClientMetrics.GrpcClientMetricsPrefix

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
    GrpcClientMetricsPrefix,
    "Distribution of the durations of serving gRPC requests.",
  )
  override val messagesSent: MetricHandle.Meter = metricsFactory.meter(
    GrpcClientMetricsPrefix :+ "messages" :+ "sent",
    "Total number of gRPC messages sent (on either type of connection).",
  )
  override val messagesReceived: MetricHandle.Meter = metricsFactory.meter(
    GrpcClientMetricsPrefix :+ "messages" :+ "received",
    "Total number of gRPC messages received (on either type of connection).",
  )
  override val messagesSentSize: MetricHandle.Histogram = metricsFactory.histogram(
    GrpcClientMetricsPrefix :+ "messages" :+ "sent" :+ Histogram.Bytes,
    "Distribution of payload sizes in gRPC messages sent (both unary and streaming).",
  )
  override val messagesReceivedSize: MetricHandle.Histogram = metricsFactory.histogram(
    GrpcClientMetricsPrefix :+ "messages" :+ "received" :+ Histogram.Bytes,
    "Distribution of payload sizes in gRPC messages received (both unary and streaming).",
  )
  override val callsStarted: MetricHandle.Meter = metricsFactory.meter(
    GrpcClientMetricsPrefix :+ "started",
    "Total number of started gRPC requests (on either type of connection).",
  )
  override val callsCompleted: MetricHandle.Meter = metricsFactory.meter(
    GrpcClientMetricsPrefix :+ "completed",
    "Total number of completed (not necessarily successful) gRPC requests.",
  )

}
