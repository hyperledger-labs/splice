// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.automation

import com.daml.network.environment.SpliceMetrics
import com.daml.metrics.api.{MetricDoc, MetricName}
import com.daml.metrics.api.MetricDoc.MetricQualification.{Latency, Traffic}
import com.daml.metrics.api.MetricHandle.{Timer, Meter}
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory

class TriggerMetrics(
    metricsFactory: CantonLabeledMetricsFactory
) {
  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "trigger"

  @MetricDoc.Tag(
    summary = "How long it takes to complete one trigger task",
    description =
      "This metric measures the time taken of individual polling iterations processed by the trigger.",
    qualification = Latency,
  )
  val latency: Timer = metricsFactory.timer(prefix :+ "latency")

  @MetricDoc.Tag(
    summary = "Number of trigger tasks that finished",
    description =
      "This metric measures the total number of tasks processed by the trigger, labeled with the outcome.",
    qualification = Traffic,
  )
  val completed: Meter = metricsFactory.meter(prefix :+ "completed")
}
