// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Meter, Timer}
import com.daml.metrics.api.MetricQualification.{Latency, Traffic}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class TriggerMetrics(
    metricsFactory: LabeledMetricsFactory
) {
  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "trigger"

  val latency: Timer = metricsFactory.timer(
    MetricInfo(
      name = prefix :+ "latency",
      summary = "How long it takes to complete one trigger task",
      description =
        "This metric measures the time taken of individual polling iterations processed by the trigger.",
      qualification = Latency,
    )
  )

  val iterations: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "iterations",
      summary = "How often a polling trigger was run",
      description =
        "This metric measures the number of individual polling iterations processed by the trigger.",
      qualification = Traffic,
    )
  )(MetricsContext.Empty)

  val completed: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "completed",
      summary = "Number of trigger tasks that finished",
      description =
        "This metric measures the total number of tasks processed by the trigger, labeled with the outcome.",
      qualification = Traffic,
    )
  )(MetricsContext.Empty)

  val attempted: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "attempted",
      summary = "Number of trigger tasks that were attempted",
      description =
        "This metric measures the total number of tasks attempted by the trigger, labeled with statusCode, and errorCodeId.",
      qualification = Traffic,
    )
  )(MetricsContext.Empty)

}
