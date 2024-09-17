// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.singlesv

import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Timer}
import com.daml.metrics.api.MetricQualification.{Debug, Latency}
import com.daml.network.environment.SpliceMetrics

class SequencerPruningMetrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "sequencer_pruning"

  val latency: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "latency",
        summary = "How long it takes to complete a single sequencer pruning request",
        description = "This metric measures the time a single sequencer pruning request takes.",
        qualification = Latency,
      )
    )

  val disabledMembers: Gauge[Int] =
    metricsFactory.gauge(
      MetricInfo(
        name = prefix :+ "disabled_members",
        summary = "How many members had to be disabled for pruning to succeed",
        description =
          "The number of members that have not caught up to the timestamp that we want to prune to and are therefore disabled..",
        qualification = Debug,
      ),
      0,
    )(MetricsContext.Empty)

  override def close() = {
    disabledMembers.close()
  }
}
