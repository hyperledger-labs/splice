// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network

import com.daml.metrics.HealthMetrics
import com.daml.metrics.api.MetricsContext
import com.daml.network.admin.api.client.{DamlGrpcClientMetrics, GrpcClientMetrics}
import com.daml.network.environment.SpliceMetrics
import com.digitalasset.canton.metrics.DbStorageMetrics
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory

/** A shared trait to capture the commonalities across our amulet node metrics. */
trait SpliceMetrics {

  def metricsFactory: CantonLabeledMetricsFactory

  def grpcClientMetrics: GrpcClientMetrics

  def healthMetrics: HealthMetrics

  def dbStorage: DbStorageMetrics
}

abstract class BaseSpliceMetrics(nodeType: String, val metricsFactory: CantonLabeledMetricsFactory)
    extends SpliceMetrics {

  private implicit val mc: MetricsContext = MetricsContext.Empty

  override def grpcClientMetrics: GrpcClientMetrics =
    new DamlGrpcClientMetrics(metricsFactory, component = nodeType)

  override def healthMetrics: HealthMetrics = new HealthMetrics(metricsFactory)

  override def dbStorage: DbStorageMetrics =
    new DbStorageMetrics(SpliceMetrics.MetricsPrefix, metricsFactory)
}
