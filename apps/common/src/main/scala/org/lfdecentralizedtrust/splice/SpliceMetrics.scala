// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice

import com.daml.metrics.HealthMetrics
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.{MetricName, MetricsContext}
import com.digitalasset.canton.environment.BaseMetrics
import com.digitalasset.canton.metrics.ActiveRequestsMetrics.GrpcServerMetricsX
import com.digitalasset.canton.metrics.{
  DbStorageHistograms,
  DbStorageMetrics,
  DeclarativeApiMetrics,
}
import org.lfdecentralizedtrust.splice.admin.api.client.{DamlGrpcClientMetrics, GrpcClientMetrics}
import org.lfdecentralizedtrust.splice.http.{HttpClientMetrics, HttpServerMetrics}

/** A shared trait to capture the commonalities across our amulet node metrics. */
trait SpliceMetrics extends BaseMetrics {

  def grpcClientMetrics: GrpcClientMetrics
  def httpServerMetrics: HttpServerMetrics
  def httpClientMetrics: HttpClientMetrics

  // Not used by splice
  override def grpcMetrics: GrpcServerMetricsX = ???
  // Not used by splice
  override def declarativeApiMetrics: DeclarativeApiMetrics = ???
}

abstract class BaseSpliceMetrics(
    nodeType: String,
    override val openTelemetryMetricsFactory: LabeledMetricsFactory,
    storageHistograms: DbStorageHistograms,
) extends SpliceMetrics {

  override val prefix = MetricName(nodeType)

  private implicit val mc: MetricsContext = MetricsContext.Empty

  override def grpcClientMetrics: GrpcClientMetrics =
    new DamlGrpcClientMetrics(openTelemetryMetricsFactory, component = nodeType)

  override def healthMetrics: HealthMetrics = new HealthMetrics(openTelemetryMetricsFactory)

  override def storageMetrics: DbStorageMetrics =
    new DbStorageMetrics(storageHistograms, openTelemetryMetricsFactory)

  override def httpServerMetrics: HttpServerMetrics = new HttpServerMetrics(
    openTelemetryMetricsFactory
  )
  override def httpClientMetrics: HttpClientMetrics = new HttpClientMetrics(
    openTelemetryMetricsFactory
  )
}
