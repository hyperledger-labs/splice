// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Traffic
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class AmuletMetrics(owner: PartyId, metricsFactory: LabeledMetricsFactory) extends AutoCloseable {
  private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "wallet"

  val unlockedAmuletGauge: Gauge[Double] =
    metricsFactory.gauge[Double](
      MetricInfo(
        prefix :+ "unlocked-amulet-balance",
        summary = "Unlocked amulet balance",
        description = "The number of unlocked amulets.",
        qualification = Traffic,
      ),
      Double.NaN,
    )(MetricsContext.Empty.withExtraLabels("owner" -> owner.toString))

  val lockedAmuletGauge: Gauge[Double] =
    metricsFactory.gauge[Double](
      MetricInfo(
        prefix :+ "locked-amulet-balance",
        summary = "Locked amulet balance",
        description = "The number of locked amulets.",
        qualification = Traffic,
      ),
      Double.NaN,
    )(MetricsContext.Empty.withExtraLabels("owner" -> owner.toString))

  override def close(): Unit = {
    unlockedAmuletGauge.close()
    lockedAmuletGauge.close()
  }
}
