// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricQualification.Saturation
import com.daml.metrics.api.{MetricHandle, MetricInfo, MetricsContext}
import com.google.common.util.concurrent.RateLimiter
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

import scala.concurrent.Future

case class SpliceRateLimitMetrics(otelFactory: LabeledMetricsFactory)(implicit mc: MetricsContext) {

  val meter: MetricHandle.Meter = otelFactory.meter(
    MetricInfo(
      SpliceMetrics.MetricsPrefix :+ "rate_limiting",
      "Rate limits applied in the node",
      Saturation,
    )
  )

}

case class SpliceRateLimitConfig(
    enabled: Boolean = true,
    ratePerSecond: Long,
)

class SpliceRateLimiter(
    name: String,
    config: SpliceRateLimitConfig,
    metrics: SpliceRateLimitMetrics,
) {

  // noinspection UnstableApiUsage
  private val rateLimiter = RateLimiter.create(config.ratePerSecond.toDouble)

  def markRun(): Boolean = {
    if (config.enabled) {
      val canRun = rateLimiter.tryAcquire()
      if (canRun) {
        metrics.meter.mark()(
          MetricsContext("result" -> "accepted", "limiter" -> name)
        )
      } else {
        metrics.meter.mark()(
          MetricsContext("result" -> "rejected", "limiter" -> name)
        )
      }
      canRun
    } else true
  }

  def runWithLimit[T](f: => Future[T]): Future[T] = {
    if (markRun()) {
      f
    } else {
      Future.failed(
        io.grpc.Status.RESOURCE_EXHAUSTED
          .withDescription("Rate limit exceeded")
          .asRuntimeException()
      )
    }
  }

}
