// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.singlesv

import com.daml.metrics.api.MetricDoc.MetricQualification.{Debug, Latency}
import com.daml.metrics.api.MetricHandle.{Gauge, Timer}
import com.daml.metrics.api.{MetricsContext, MetricDoc, MetricName}
import com.daml.network.environment.SpliceMetrics
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory

class SequencerPruningMetrics(metricsFactory: CantonLabeledMetricsFactory) extends AutoCloseable {

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "sequencer_pruning"

  @MetricDoc.Tag(
    summary = "How long it takes to complete a single sequencer pruning request",
    description = "This metric measures the time a single sequencer pruning request takes.",
    qualification = Latency,
  )
  val latency: Timer =
    metricsFactory.timer(prefix :+ "latency")

  @MetricDoc.Tag(
    summary = "How many members had to be disabled for pruning to succeed",
    description =
      "The number of members that have not caught up to the timestamp that we want to prune to and are therefore disabled..",
    qualification = Debug,
  )
  val disabledMembers: Gauge[Int] =
    metricsFactory.gauge(prefix :+ "disabled_members", 0)(MetricsContext.Empty)

  override def close() = {
    disabledMembers.close()
  }
}
