package com.daml.network.sv.metrics

import com.daml.metrics.HealthMetrics
import com.daml.metrics.api.MetricName
import com.daml.metrics.grpc.GrpcServerMetrics
import com.daml.network.CoinNodeMetrics
import com.digitalasset.canton.metrics.DbStorageMetrics
import com.digitalasset.canton.metrics.MetricHandle.CantonDropwizardMetricsFactory

/** Modelled after [[com.digitalasset.canton.domain.metrics.DomainMetrics]].
  *
  * This is only a bare-bones implementation so the code compiles so far.
  */
class SvAppMetrics(
    override val prefix: MetricName,
    override val dropwizardFactory: CantonDropwizardMetricsFactory,
    override val grpcMetrics: GrpcServerMetrics,
    override val healthMetrics: HealthMetrics,
) extends CoinNodeMetrics {
  override object dbStorage extends DbStorageMetrics(prefix, dropwizardFactory)
}
