// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.{MetricInfo, MetricsContext}
import com.daml.metrics.api.MetricQualification.Saturation
import org.lfdecentralizedtrust.splice.BaseSpliceMetrics
import com.digitalasset.canton.metrics.DbStorageHistograms

/** Modelled after [[com.digitalasset.canton.synchronizer.metrics.DomainMetrics]].
  */
class ValidatorAppMetrics(
    metricsFactory: LabeledMetricsFactory,
    storageHistograms: DbStorageHistograms,
) extends BaseSpliceMetrics("validator", metricsFactory, storageHistograms) {

  val numberOfPartiesGauge: Gauge[Double] =
    metricsFactory.gauge[Double](
      MetricInfo(
        prefix :+ "synchronizer-topology-num-parties",
        summary = "Total number of parties",
        description = "The total number of parties allocated on the global synchronizer.",
        qualification = Saturation,
      ),
      Double.NaN,
    )(MetricsContext.Empty)

}
