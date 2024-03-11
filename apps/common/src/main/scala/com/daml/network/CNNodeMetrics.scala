package com.daml.network

import com.daml.metrics.HealthMetrics
import com.daml.metrics.api.MetricsContext
import com.daml.network.admin.api.client.{DamlGrpcClientMetrics, GrpcClientMetrics}
import com.daml.network.environment.CNMetrics
import com.digitalasset.canton.metrics.DbStorageMetrics
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory

/** A shared trait to capture the commonalities across our coin node metrics. */
trait CNNodeMetrics {

  def metricsFactory: CantonLabeledMetricsFactory

  def grpcClientMetrics: GrpcClientMetrics

  def healthMetrics: HealthMetrics

  def dbStorage: DbStorageMetrics
}

abstract class BaseCNNodeMetrics(nodeType: String, val metricsFactory: CantonLabeledMetricsFactory)
    extends CNNodeMetrics {

  private implicit val mc: MetricsContext = MetricsContext.Empty

  override def grpcClientMetrics: GrpcClientMetrics =
    new DamlGrpcClientMetrics(metricsFactory, component = nodeType)

  override def healthMetrics: HealthMetrics = new HealthMetrics(metricsFactory)

  override def dbStorage: DbStorageMetrics =
    new DbStorageMetrics(CNMetrics.MetricsPrefix, metricsFactory)
}
