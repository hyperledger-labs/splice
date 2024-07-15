// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.store.db

import com.daml.metrics.api.MetricDoc.MetricQualification.Latency
import com.daml.metrics.api.MetricHandle.Gauge
import com.daml.metrics.api.{MetricsContext, MetricDoc, MetricName}
import com.daml.network.environment.SpliceMetrics
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory

class DbSvDsoStoreMetrics(metricsFactory: CantonLabeledMetricsFactory) extends AutoCloseable {

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "sv_dso_store"

  @MetricDoc.Tag(
    summary = "Latest open mining round",
    description =
      "The number of the latest open mining round (not necessarily active yet) ingested by the store.",
    qualification = Latency,
  )
  val latestOpenMiningRound: Gauge[Long] =
    metricsFactory.gauge(prefix :+ "latest-open-mining-round", -1L)(MetricsContext.Empty)

  override def close() = {
    latestOpenMiningRound.close()
  }
}
