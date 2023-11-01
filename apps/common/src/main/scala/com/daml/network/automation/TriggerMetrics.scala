package com.daml.network.automation

import com.daml.network.environment.CNMetrics
import com.daml.metrics.api.{MetricDoc, MetricName}
import com.daml.metrics.api.MetricDoc.MetricQualification.{Latency, Traffic, Debug, Errors}
import com.daml.metrics.api.MetricHandle.{Timer, Meter}
import com.digitalasset.canton.metrics.MetricHandle.LabeledMetricsFactory

class PollingTriggerMetrics(
    metricsFactory: LabeledMetricsFactory
) {
  val prefix: MetricName = CNMetrics.MetricsPrefix :+ "trigger"

  @MetricDoc.Tag(
    summary = "How long it takes to complete one iteration of performWork",
    description =
      "This metric measures the time taken of individual polling iterations processed by the trigger.",
    qualification = Latency,
  )
  val iterationLatency: Timer = metricsFactory.timer(prefix :+ "iteration-latency")

  @MetricDoc.Tag(
    summary = "Number of iterations of performWork that succeeded",
    description =
      "This metric measures the total number of successful polling iterations processed by the trigger.",
    qualification = Traffic,
  )
  val iterationsCompleted: Meter = metricsFactory.meter(prefix :+ "iterations-completed")

  @MetricDoc.Tag(
    summary = "Number of performWork calls that failed with a retriable exception",
    description = "",
    qualification = Debug,
  )
  val iterationsRetried: Meter = metricsFactory.meter(prefix :+ "iterations-retried")

  @MetricDoc.Tag(
    summary = "Number of performWork calls that failed with a non-retriable exception",
    description = "",
    qualification = Errors,
  )
  val iterationsFailed: Meter = metricsFactory.meter(prefix :+ "iterations-failed")
}
