package com.daml.network

import com.daml.metrics.HealthMetrics
import com.daml.metrics.grpc.{DamlGrpcServerMetrics, GrpcServerMetrics}
import com.daml.network.environment.CNMetrics
import com.digitalasset.canton.metrics.DbStorageMetrics
import com.digitalasset.canton.metrics.MetricHandle.LabeledMetricsFactory

/** A shared trait to capture the commonalities across our coin node metrics. */
trait CNNodeMetrics {

  def metricsFactory: LabeledMetricsFactory

  def grpcMetrics: GrpcServerMetrics

  def healthMetrics: HealthMetrics

  def dbStorage: DbStorageMetrics
}

abstract class BaseCNNodeMetrics(nodeType: String, val metricsFactory: LabeledMetricsFactory)
    extends CNNodeMetrics {

  override def grpcMetrics: GrpcServerMetrics =
    new DamlGrpcServerMetrics(metricsFactory, component = nodeType)

  override def healthMetrics: HealthMetrics = new HealthMetrics(metricsFactory)

  override def dbStorage: DbStorageMetrics =
    new DbStorageMetrics(CNMetrics.MetricsPrefix, metricsFactory)
}
