package com.daml.network.directory.metrics

import com.daml.network.BaseCNNodeMetrics
import com.digitalasset.canton.metrics.MetricHandle.LabeledMetricsFactory

/** Modelled after [[com.digitalasset.canton.domain.metrics.DomainMetrics]].
  *
  * This is only a bare-bones implementation so the code compiles so far.
  */
class DirectoryAppMetrics(
    override val metricsFactory: LabeledMetricsFactory
) extends BaseCNNodeMetrics("directory", metricsFactory)
