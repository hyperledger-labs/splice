package com.daml.network

import com.daml.metrics.HealthMetrics
import com.daml.metrics.api.MetricName
import com.daml.metrics.grpc.GrpcServerMetrics
import com.digitalasset.canton.metrics.DbStorageMetrics
import com.digitalasset.canton.metrics.MetricHandle.CantonDropwizardMetricsFactory

/** A shared trait to capture the commonalities across our coin node metrics. */
trait CoinNodeMetrics {
  def prefix: MetricName

  def dropwizardFactory: CantonDropwizardMetricsFactory

  def grpcMetrics: GrpcServerMetrics

  def healthMetrics: HealthMetrics

  def dbStorage: DbStorageMetrics
}
