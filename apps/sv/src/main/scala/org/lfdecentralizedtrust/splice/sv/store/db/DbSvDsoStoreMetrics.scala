// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store.db

import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Latency
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class DbSvDsoStoreMetrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "sv_dso_store"

  val latestOpenMiningRound: Gauge[Long] =
    metricsFactory.gauge(
      MetricInfo(
        name = prefix :+ "latest-open-mining-round",
        summary = "Latest open mining round",
        description =
          "The number of the latest open mining round (not necessarily active yet) ingested by the store.",
        qualification = Latency,
      ),
      -1L,
    )(MetricsContext.Empty)

  val latestIssuingMiningRound: Gauge[Long] =
    metricsFactory.gauge(
      MetricInfo(
        name = prefix :+ "latest-issuing-mining-round",
        summary = "Latest issuing mining round",
        description =
          "The number of the latest issuing mining round (not necessarily active yet) ingested by the store.",
        qualification = Latency,
      ),
      -1L,
    )(MetricsContext.Empty)

  override def close() = {
    latestOpenMiningRound.close()
    latestIssuingMiningRound.close()
  }
}
