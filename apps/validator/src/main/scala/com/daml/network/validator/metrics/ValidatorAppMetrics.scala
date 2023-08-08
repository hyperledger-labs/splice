package com.daml.network.validator.metrics

import com.daml.network.BaseCNNodeMetrics
import com.digitalasset.canton.metrics.MetricHandle.LabeledMetricsFactory

/** Modelled after [[com.digitalasset.canton.domain.metrics.DomainMetrics]].
  *
  * This is only a bare-bones implementation so the code compiles so far.
  */
class ValidatorAppMetrics(
    metricsFactory: LabeledMetricsFactory
) extends BaseCNNodeMetrics("validator", metricsFactory)
