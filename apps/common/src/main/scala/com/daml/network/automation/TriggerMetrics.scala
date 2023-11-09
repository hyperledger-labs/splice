package com.daml.network.automation

import com.daml.network.environment.CNMetrics
import com.daml.metrics.api.{MetricDoc, MetricName}
import com.daml.metrics.api.MetricDoc.MetricQualification.{Latency, Traffic}
import com.daml.metrics.api.MetricHandle.{Timer, Meter}
import com.digitalasset.canton.metrics.MetricHandle.LabeledMetricsFactory

class TriggerMetrics(
    metricsFactory: LabeledMetricsFactory
) {
  val prefix: MetricName = CNMetrics.MetricsPrefix :+ "trigger"

  @MetricDoc.Tag(
    summary = "How long it takes to complete one trigger task",
    description =
      "This metric measures the time taken of individual polling iterations processed by the trigger.",
    qualification = Latency,
  )
  val latency: Timer = metricsFactory.timer(prefix :+ "latency")

  @MetricDoc.Tag(
    summary = "Number of trigger tasks that succeeded",
    description =
      "This metric measures the total number of successful tasks processed by the trigger.",
    qualification = Traffic,
  )
  val completed: Meter = metricsFactory.meter(prefix :+ "completed")
}
