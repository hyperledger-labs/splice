package com.daml.network.splitwell.metrics

import com.daml.network.BaseCNNodeMetrics
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory

/** Modelled after [[com.digitalasset.canton.domain.metrics.DomainMetrics]].
  *
  * This is only a bare-bones implementation so the code compiles so far.
  */
class SplitwellAppMetrics(
    metricsFactory: CantonLabeledMetricsFactory
) extends BaseCNNodeMetrics("splitwell", metricsFactory) {}
